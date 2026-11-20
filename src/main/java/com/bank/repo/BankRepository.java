package com.bank.repo;

import com.bank.model.Account;
import com.bank.model.Beneficiary;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.store.TableStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Keeps all bank data in memory and writes every change through to the {@link TableStore}
 * (Google Sheet or CSV). All access goes through {@link #lock} so balances stay consistent.
 */
@Repository
public class BankRepository {

    public static final String CUSTOMERS = "Customers";
    public static final String ACCOUNTS = "Accounts";
    public static final String BENEFICIARIES = "Beneficiaries";
    public static final String TRANSACTIONS = "Transactions";

    private static final Logger log = LoggerFactory.getLogger(BankRepository.class);

    public final Object lock = new Object();

    private final TableStore store;
    private final PasswordEncoder encoder;
    private final String seedPassword;

    private final Map<String, Customer> customers = new LinkedHashMap<>();
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Beneficiary> beneficiaries = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();

    public BankRepository(TableStore store, PasswordEncoder encoder,
                          @Value("${banking.seed.default-password}") String seedPassword) {
        this.store = store;
        this.encoder = encoder;
        this.seedPassword = seedPassword;
    }

    /** Reads older than this are served from memory; writes always re-read first. */
    private static final long READ_TTL_MS = 3000;
    private long lastRefresh;

    @PostConstruct
    void load() {
        synchronized (lock) {
            readTables();
            if (accounts.isEmpty()) seed();
            log.info("Loaded {} customers, {} accounts, {} beneficiaries, {} transactions",
                    customers.size(), accounts.size(), beneficiaries.size(), transactions.size());
        }
    }

    private void readTables() {
        List<List<String>> c = store.readAll(CUSTOMERS), a = store.readAll(ACCOUNTS),
                b = store.readAll(BENEFICIARIES), t = store.readAll(TRANSACTIONS);
        customers.clear(); accounts.clear(); beneficiaries.clear(); transactions.clear();
        c.forEach(r -> put(customers, Customer.fromRow(r), Customer::customerId));
        a.forEach(r -> put(accounts, Account.fromRow(r), Account::accountNumber));
        b.forEach(r -> put(beneficiaries, Beneficiary.fromRow(r), Beneficiary::beneficiaryId));
        t.forEach(r -> transactions.add(Transaction.fromRow(r)));
        lastRefresh = System.currentTimeMillis();
    }

    /**
     * Re-reads every table so this instance sees changes made by other server instances (e.g. on Vercel,
     * where several instances share one Google Sheet). Call while holding {@link #lock}.
     * @param beforeWrite true before any change: always re-read, so we never save over newer data
     */
    public void refresh(boolean beforeWrite) {
        if (!beforeWrite && System.currentTimeMillis() - lastRefresh < READ_TTL_MS) return;
        if (store.readAll(ACCOUNTS).isEmpty() && !accounts.isEmpty()) {
            // Never replace good data with an empty read (e.g. the sheet was cleared by hand).
            log.warn("Accounts table read back empty; keeping the data already in memory");
            return;
        }
        readTables();
    }

    /** Creates the 10 sample customers/accounts the first time the app runs against an empty store. */
    private void seed() {
        String[][] sample = {
                {"Aarav Sharma", "aarav.sharma@example.com", "9000000001", "SAVINGS", "25000.00"},
                {"Priya Patel", "priya.patel@example.com", "9000000002", "SAVINGS", "18500.50"},
                {"Rahul Verma", "rahul.verma@example.com", "9000000003", "CURRENT", "72000.00"},
                {"Ananya Iyer", "ananya.iyer@example.com", "9000000004", "SAVINGS", "9400.75"},
                {"Vikram Singh", "vikram.singh@example.com", "9000000005", "CURRENT", "150000.00"},
                {"Sneha Reddy", "sneha.reddy@example.com", "9000000006", "SAVINGS", "31250.00"},
                {"Arjun Nair", "arjun.nair@example.com", "9000000007", "SAVINGS", "5600.00"},
                {"Kavya Menon", "kavya.menon@example.com", "9000000008", "SAVINGS", "44800.25"},
                {"Rohan Gupta", "rohan.gupta@example.com", "9000000009", "CURRENT", "98000.00"},
                {"Meera Joshi", "meera.joshi@example.com", "9000000010", "SAVINGS", "12000.00"},
        };
        String now = Instant.now().toString();
        String hash = encoder.encode(seedPassword);
        for (int i = 0; i < sample.length; i++) {
            String[] s = sample[i];
            String custId = String.format("CUST%03d", i + 1);
            String accNo = String.valueOf(1000000001L + i);
            put(customers, new Customer(custId, s[0], s[1], s[2], hash, now), Customer::customerId);
            put(accounts, new Account(accNo, custId, s[0], s[3], new BigDecimal(s[4]), now), Account::accountNumber);
        }
        flush(CUSTOMERS);
        flush(ACCOUNTS);
        flush(BENEFICIARIES);
        flush(TRANSACTIONS);
        log.info("Seeded 10 sample accounts (password for all sample customers: {})", seedPassword);
    }

    private static <T> void put(Map<String, T> map, T item, Function<T, String> key) {
        map.put(key.apply(item), item);
    }

    // ---------- persistence ----------

    public void flush(String table) {
        switch (table) {
            case CUSTOMERS -> store.writeAll(CUSTOMERS, Customer.HEADER, customers.values().stream().map(Customer::toRow).toList());
            case ACCOUNTS -> store.writeAll(ACCOUNTS, Account.HEADER, accounts.values().stream().map(Account::toRow).toList());
            case BENEFICIARIES -> store.writeAll(BENEFICIARIES, Beneficiary.HEADER, beneficiaries.values().stream().map(Beneficiary::toRow).toList());
            case TRANSACTIONS -> store.writeAll(TRANSACTIONS, Transaction.HEADER, transactions.stream().map(Transaction::toRow).toList());
            default -> throw new IllegalArgumentException(table);
        }
    }

    // ---------- customers ----------

    public Optional<Customer> findCustomer(String id) { return Optional.ofNullable(customers.get(id)); }

    public Optional<Customer> findCustomerByEmail(String email) {
        return customers.values().stream().filter(c -> c.email().equalsIgnoreCase(email)).findFirst();
    }

    public void saveCustomer(Customer c) { customers.put(c.customerId(), c); }

    public String nextCustomerId() {
        int max = customers.keySet().stream().mapToInt(k -> Integer.parseInt(k.substring(4))).max().orElse(0);
        return String.format("CUST%03d", max + 1);
    }

    public void removeCustomer(String id) { customers.remove(id); }

    // ---------- accounts ----------

    public Collection<Account> allAccounts() { return accounts.values(); }

    public Optional<Account> findAccount(String accountNumber) { return Optional.ofNullable(accounts.get(accountNumber)); }

    public List<Account> accountsOf(String customerId) {
        return accounts.values().stream().filter(a -> a.customerId().equals(customerId)).toList();
    }

    public void saveAccount(Account a) { accounts.put(a.accountNumber(), a); }

    public void removeAccount(String accountNumber) { accounts.remove(accountNumber); }

    public String nextAccountNumber() {
        long max = accounts.keySet().stream().mapToLong(Long::parseLong).max().orElse(1000000000L);
        return String.valueOf(max + 1);
    }

    // ---------- beneficiaries ----------

    public List<Beneficiary> beneficiariesOf(String customerId) {
        return beneficiaries.values().stream().filter(b -> b.customerId().equals(customerId)).toList();
    }

    public Optional<Beneficiary> findBeneficiary(String id) { return Optional.ofNullable(beneficiaries.get(id)); }

    public void saveBeneficiary(Beneficiary b) { beneficiaries.put(b.beneficiaryId(), b); }

    public Beneficiary removeBeneficiary(String id) { return beneficiaries.remove(id); }

    // ---------- transactions ----------

    public void addTransaction(Transaction t) { transactions.add(t); }

    public void removeLastTransaction() { transactions.removeLast(); }

    public List<Transaction> recentTransactions(int limit) {
        return transactions.reversed().stream().limit(limit).toList();
    }

    public List<Transaction> transactionsFor(Set<String> accountNumbers, int limit) {
        return transactions.reversed().stream()
                .filter(t -> accountNumbers.contains(t.fromAccount()) || accountNumbers.contains(t.toAccount()))
                .limit(limit).toList();
    }
}
