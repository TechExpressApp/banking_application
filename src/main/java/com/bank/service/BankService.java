package com.bank.service;

import com.bank.model.Account;
import com.bank.model.Beneficiary;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.repo.BankRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bank rules. Every change runs in a database transaction; transfers lock both account rows first,
 * so concurrent transfers (even from different server instances) can never overspend or lose money.
 */
@Service
public class BankService {

    private static final BigDecimal MAX_TRANSFER = new BigDecimal("1000000.00");

    private final BankRepository repo;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public BankService(BankRepository repo, PasswordEncoder encoder, ApplicationEventPublisher events, TransactionTemplate tx) {
        this.repo = repo;
        this.encoder = encoder;
        this.events = events;
        this.tx = tx;
    }

    // ===================== registration & login =====================

    public record Registration(Customer customer, Account account) {}

    public Registration register(String fullName, String email, String phone, String password,
                                 String accountType, BigDecimal openingDeposit) {
        String type = accountType == null || accountType.isBlank() ? "SAVINGS" : accountType.trim().toUpperCase();
        if (!Set.of("SAVINGS", "CURRENT").contains(type)) throw BankException.badRequest("Account type must be SAVINGS or CURRENT");
        BigDecimal deposit = openingDeposit == null ? BigDecimal.ZERO : scale(openingDeposit);
        if (deposit.signum() < 0) throw BankException.badRequest("Opening deposit cannot be negative");
        String cleanEmail = email.trim().toLowerCase();
        String hash = encoder.encode(password);

        // IDs are "highest + 1"; if another instance takes the same ID at the same moment, try again.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (repo.findCustomerByEmail(cleanEmail).isPresent()) {
                throw BankException.conflict("A customer with this e-mail is already registered");
            }
            try {
                return tx.execute(s -> {
                    String now = Instant.now().toString();
                    Customer c = new Customer(repo.nextCustomerId(), fullName.trim(), cleanEmail,
                            phone == null ? "" : phone.trim(), hash, now);
                    Account a = new Account(repo.nextAccountNumber(), c.customerId(), c.fullName(), type, deposit, now);
                    repo.insertCustomer(c);
                    repo.insertAccount(a);
                    return new Registration(c, a);
                });
            } catch (DuplicateKeyException e) {
                // e-mail taken (checked again at the top of the loop) or ID collision: retry
            }
        }
        throw BankException.conflict("Registration is busy, please try again");
    }

    public Customer authenticate(String email, String password) {
        return repo.findCustomerByEmail(email.trim())
                .filter(c -> encoder.matches(password, c.passwordHash()))
                .orElseThrow(() -> BankException.unauthorized("Invalid e-mail or password"));
    }

    public Customer customer(String customerId) {
        return repo.findCustomer(customerId).orElseThrow(() -> BankException.notFound("Customer not found"));
    }

    public List<Account> accountsOf(String customerId) {
        return repo.accountsOf(customerId);
    }

    // ===================== beneficiaries =====================

    public List<Beneficiary> beneficiaries(String customerId) {
        return repo.beneficiariesOf(customerId);
    }

    public Beneficiary addBeneficiary(String customerId, String accountNumber, String nickname) {
        Account target = repo.findAccount(accountNumber.trim())
                .orElseThrow(() -> BankException.notFound("No account " + accountNumber + " exists in this bank"));
        if (target.customerId().equals(customerId)) {
            throw BankException.badRequest("You cannot add your own account as a beneficiary");
        }
        String nick = nickname == null || nickname.isBlank() ? target.holderName() : nickname.trim();
        Beneficiary b = new Beneficiary("BEN-" + UUID.randomUUID().toString().substring(0, 8), customerId, nick,
                target.accountNumber(), target.holderName(), Instant.now().toString());
        try {
            repo.insertBeneficiary(b);
        } catch (DuplicateKeyException e) {
            throw BankException.conflict("This account is already one of your beneficiaries");
        }
        return b;
    }

    public void removeBeneficiary(String customerId, String beneficiaryId) {
        if (!repo.deleteBeneficiary(beneficiaryId, customerId)) throw BankException.notFound("Beneficiary not found");
    }

    // ===================== transfers =====================

    /** Customer transfer: from one of the customer's own accounts to one of their registered beneficiaries. */
    public Transaction transferToBeneficiary(String customerId, String fromAccount, String beneficiaryId,
                                             BigDecimal amount, String note) {
        Account from = repo.findAccount(fromAccount)
                .orElseThrow(() -> BankException.notFound("Source account not found"));
        if (!from.customerId().equals(customerId)) {
            throw BankException.forbidden("You can only transfer from your own accounts");
        }
        Beneficiary b = repo.findBeneficiary(beneficiaryId)
                .filter(x -> x.customerId().equals(customerId))
                .orElseThrow(() -> BankException.notFound("Beneficiary not found - add the beneficiary first"));
        return transfer(from.accountNumber(), b.accountNumber(), amount, customerId, note);
    }

    /** Transfer between any two accounts in the bank, atomically. */
    public Transaction transfer(String fromAccount, String toAccount, BigDecimal rawAmount, String initiatedBy, String note) {
        if (rawAmount == null) throw BankException.badRequest("Amount is required");
        BigDecimal amount = scale(rawAmount);
        if (amount.signum() <= 0) throw BankException.badRequest("Amount must be greater than zero");
        if (amount.compareTo(MAX_TRANSFER) > 0) throw BankException.badRequest("Amount exceeds the per-transfer limit of " + MAX_TRANSFER);
        if (fromAccount.equals(toAccount)) throw BankException.badRequest("Source and target accounts must be different");

        Transaction txn = tx.execute(s -> {
            // Check both exist (clear error messages), then lock both rows in a fixed order to avoid deadlocks.
            repo.findAccount(fromAccount).orElseThrow(() -> BankException.notFound("Source account " + fromAccount + " not found"));
            repo.findAccount(toAccount).orElseThrow(() -> BankException.notFound("Target account " + toAccount + " not found"));
            boolean fromFirst = fromAccount.compareTo(toAccount) < 0;
            Account first = repo.findAccountForUpdate(fromFirst ? fromAccount : toAccount).orElseThrow();
            Account second = repo.findAccountForUpdate(fromFirst ? toAccount : fromAccount).orElseThrow();
            Account from = fromFirst ? first : second;

            if (from.balance().compareTo(amount) < 0) {
                throw BankException.badRequest("Insufficient funds: available balance is " + from.balance());
            }
            Transaction t = new Transaction("TXN-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(),
                    Instant.now().toString(), fromAccount, toAccount, amount, initiatedBy, note == null ? "" : note.trim());
            repo.addToBalance(fromAccount, amount.negate());
            repo.addToBalance(toAccount, amount);
            repo.insertTransaction(t);
            return t;
        });
        events.publishEvent(new TransferCompletedEvent(txn));   // after commit
        return txn;
    }

    // ===================== queries =====================

    public List<Account> allAccounts() {
        return repo.allAccounts();
    }

    public Account account(String accountNumber) {
        return repo.findAccount(accountNumber).orElseThrow(() -> BankException.notFound("Account " + accountNumber + " not found"));
    }

    public Beneficiary beneficiary(String customerId, String beneficiaryId) {
        return repo.findBeneficiary(beneficiaryId).filter(b -> b.customerId().equals(customerId))
                .orElseThrow(() -> BankException.notFound("Beneficiary " + beneficiaryId + " not found"));
    }

    public List<Transaction> transactionsOfAccount(String accountNumber, int limit) {
        return repo.transactionsFor(Set.of(accountNumber), limit);
    }

    public List<Transaction> recentTransactions(int limit) {
        return repo.recentTransactions(limit);
    }

    public List<Transaction> transactionsOf(String customerId, int limit) {
        Set<String> mine = repo.accountsOf(customerId).stream().map(Account::accountNumber).collect(Collectors.toSet());
        return repo.transactionsFor(mine, limit);
    }

    private static BigDecimal scale(BigDecimal v) {
        if (v.stripTrailingZeros().scale() > 2) throw BankException.badRequest("Amount can have at most 2 decimal places");
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
