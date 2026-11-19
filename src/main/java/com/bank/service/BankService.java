package com.bank.service;

import com.bank.model.Account;
import com.bank.model.Beneficiary;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.repo.BankRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BankService {

    private static final BigDecimal MAX_TRANSFER = new BigDecimal("1000000.00");

    private final BankRepository repo;
    private final PasswordEncoder encoder;
    private final ApplicationEventPublisher events;

    public BankService(BankRepository repo, PasswordEncoder encoder, ApplicationEventPublisher events) {
        this.repo = repo;
        this.encoder = encoder;
        this.events = events;
    }

    // ===================== registration & login =====================

    public record Registration(Customer customer, Account account) {}

    public Registration register(String fullName, String email, String phone, String password,
                                 String accountType, BigDecimal openingDeposit) {
        String type = accountType == null || accountType.isBlank() ? "SAVINGS" : accountType.trim().toUpperCase();
        if (!Set.of("SAVINGS", "CURRENT").contains(type)) throw BankException.badRequest("Account type must be SAVINGS or CURRENT");
        BigDecimal deposit = openingDeposit == null ? BigDecimal.ZERO : scale(openingDeposit);
        if (deposit.signum() < 0) throw BankException.badRequest("Opening deposit cannot be negative");

        synchronized (repo.lock) {
            if (repo.findCustomerByEmail(email).isPresent()) {
                throw BankException.conflict("A customer with this e-mail is already registered");
            }
            String now = Instant.now().toString();
            Customer c = new Customer(repo.nextCustomerId(), fullName.trim(), email.trim().toLowerCase(),
                    phone == null ? "" : phone.trim(), encoder.encode(password), now);
            Account a = new Account(repo.nextAccountNumber(), c.customerId(), c.fullName(), type, deposit, now);
            repo.saveCustomer(c);
            repo.saveAccount(a);
            try {
                repo.flush(BankRepository.CUSTOMERS);
                repo.flush(BankRepository.ACCOUNTS);
            } catch (RuntimeException e) {
                repo.removeCustomer(c.customerId());
                repo.removeAccount(a.accountNumber());
                throw e;
            }
            return new Registration(c, a);
        }
    }

    public Customer authenticate(String email, String password) {
        synchronized (repo.lock) {
            return repo.findCustomerByEmail(email.trim())
                    .filter(c -> encoder.matches(password, c.passwordHash()))
                    .orElseThrow(() -> BankException.unauthorized("Invalid e-mail or password"));
        }
    }

    public Customer customer(String customerId) {
        synchronized (repo.lock) {
            return repo.findCustomer(customerId).orElseThrow(() -> BankException.notFound("Customer not found"));
        }
    }

    public List<Account> accountsOf(String customerId) {
        synchronized (repo.lock) {
            return repo.accountsOf(customerId);
        }
    }

    // ===================== beneficiaries =====================

    public List<Beneficiary> beneficiaries(String customerId) {
        synchronized (repo.lock) {
            return repo.beneficiariesOf(customerId);
        }
    }

    public Beneficiary addBeneficiary(String customerId, String accountNumber, String nickname) {
        synchronized (repo.lock) {
            Account target = repo.findAccount(accountNumber.trim())
                    .orElseThrow(() -> BankException.notFound("No account " + accountNumber + " exists in this bank"));
            if (target.customerId().equals(customerId)) {
                throw BankException.badRequest("You cannot add your own account as a beneficiary");
            }
            boolean duplicate = repo.beneficiariesOf(customerId).stream()
                    .anyMatch(b -> b.accountNumber().equals(target.accountNumber()));
            if (duplicate) throw BankException.conflict("This account is already one of your beneficiaries");

            String nick = nickname == null || nickname.isBlank() ? target.holderName() : nickname.trim();
            Beneficiary b = new Beneficiary("BEN-" + UUID.randomUUID().toString().substring(0, 8), customerId, nick,
                    target.accountNumber(), target.holderName(), Instant.now().toString());
            repo.saveBeneficiary(b);
            try {
                repo.flush(BankRepository.BENEFICIARIES);
            } catch (RuntimeException e) {
                repo.removeBeneficiary(b.beneficiaryId());
                throw e;
            }
            return b;
        }
    }

    public void removeBeneficiary(String customerId, String beneficiaryId) {
        synchronized (repo.lock) {
            Beneficiary b = repo.findBeneficiary(beneficiaryId)
                    .filter(x -> x.customerId().equals(customerId))
                    .orElseThrow(() -> BankException.notFound("Beneficiary not found"));
            repo.removeBeneficiary(b.beneficiaryId());
            try {
                repo.flush(BankRepository.BENEFICIARIES);
            } catch (RuntimeException e) {
                repo.saveBeneficiary(b);
                throw e;
            }
        }
    }

    // ===================== transfers =====================

    /** Customer transfer: from one of the customer's own accounts to one of their registered beneficiaries. */
    public Transaction transferToBeneficiary(String customerId, String fromAccount, String beneficiaryId,
                                             BigDecimal amount, String note) {
        synchronized (repo.lock) {
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
    }

    /** Teller/bank transfer: between any two accounts in the bank. */
    public Transaction transfer(String fromAccount, String toAccount, BigDecimal rawAmount, String initiatedBy, String note) {
        if (rawAmount == null) throw BankException.badRequest("Amount is required");
        BigDecimal amount = scale(rawAmount);
        if (amount.signum() <= 0) throw BankException.badRequest("Amount must be greater than zero");
        if (amount.compareTo(MAX_TRANSFER) > 0) throw BankException.badRequest("Amount exceeds the per-transfer limit of " + MAX_TRANSFER);
        if (fromAccount.equals(toAccount)) throw BankException.badRequest("Source and target accounts must be different");

        synchronized (repo.lock) {
            Account from = repo.findAccount(fromAccount).orElseThrow(() -> BankException.notFound("Source account " + fromAccount + " not found"));
            Account to = repo.findAccount(toAccount).orElseThrow(() -> BankException.notFound("Target account " + toAccount + " not found"));
            if (from.balance().compareTo(amount) < 0) {
                throw BankException.badRequest("Insufficient funds: available balance is " + from.balance());
            }

            Transaction txn = new Transaction("TXN-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase(),
                    Instant.now().toString(), from.accountNumber(), to.accountNumber(), amount,
                    initiatedBy, note == null ? "" : note.trim());

            repo.saveAccount(from.withBalance(from.balance().subtract(amount)));
            repo.saveAccount(to.withBalance(to.balance().add(amount)));
            repo.addTransaction(txn);
            try {
                repo.flush(BankRepository.ACCOUNTS);
                repo.flush(BankRepository.TRANSACTIONS);
            } catch (RuntimeException e) {
                // roll back the in-memory state and try to restore the stored balances
                repo.saveAccount(from);
                repo.saveAccount(to);
                repo.removeLastTransaction();
                try { repo.flush(BankRepository.ACCOUNTS); } catch (RuntimeException ignored) { }
                throw new BankException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Transfer failed while saving, no money was moved: " + e.getMessage());
            }
            events.publishEvent(new TransferCompletedEvent(txn));
            return txn;
        }
    }

    // ===================== queries =====================

    public List<Account> allAccounts() {
        synchronized (repo.lock) {
            return List.copyOf(repo.allAccounts());
        }
    }

    public Account account(String accountNumber) {
        synchronized (repo.lock) {
            return repo.findAccount(accountNumber).orElseThrow(() -> BankException.notFound("Account " + accountNumber + " not found"));
        }
    }

    public Beneficiary beneficiary(String customerId, String beneficiaryId) {
        synchronized (repo.lock) {
            return repo.findBeneficiary(beneficiaryId).filter(b -> b.customerId().equals(customerId))
                    .orElseThrow(() -> BankException.notFound("Beneficiary " + beneficiaryId + " not found"));
        }
    }

    public List<Transaction> transactionsOfAccount(String accountNumber, int limit) {
        synchronized (repo.lock) {
            return repo.transactionsFor(Set.of(accountNumber), limit);
        }
    }

    public List<Transaction> recentTransactions(int limit) {
        synchronized (repo.lock) {
            return repo.recentTransactions(limit);
        }
    }

    public List<Transaction> transactionsOf(String customerId, int limit) {
        synchronized (repo.lock) {
            Set<String> mine = repo.accountsOf(customerId).stream().map(Account::accountNumber).collect(Collectors.toSet());
            return repo.transactionsFor(mine, limit);
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        if (v.scale() > 2 && v.stripTrailingZeros().scale() > 2) {
            throw BankException.badRequest("Amount can have at most 2 decimal places");
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }
}
