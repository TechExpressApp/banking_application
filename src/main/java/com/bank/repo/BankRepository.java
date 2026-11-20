package com.bank.repo;

import com.bank.model.Account;
import com.bank.model.Beneficiary;
import com.bank.model.Customer;
import com.bank.model.Transaction;
import com.bank.store.CsvTableStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bank data in a SQL database (Supabase/PostgreSQL in production, H2 locally). Tables are in the "bank" schema
 * (see schema.sql). Callers wrap multi-step changes in a transaction; {@link #findAccountForUpdate} locks a row.
 */
@Repository
public class BankRepository {

    private static final Logger log = LoggerFactory.getLogger(BankRepository.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final String seedPassword;
    private final String importDir;

    private static final RowMapper<Customer> CUSTOMER = (rs, i) -> new Customer(rs.getString("customer_id"),
            rs.getString("full_name"), rs.getString("email"), rs.getString("phone"), rs.getString("password_hash"),
            rs.getString("created_at"));
    private static final RowMapper<Account> ACCOUNT = (rs, i) -> new Account(rs.getString("account_number"),
            rs.getString("customer_id"), rs.getString("holder_name"), rs.getString("account_type"),
            rs.getBigDecimal("balance"), rs.getString("created_at"));
    private static final RowMapper<Beneficiary> BENEFICIARY = (rs, i) -> new Beneficiary(rs.getString("beneficiary_id"),
            rs.getString("customer_id"), rs.getString("nickname"), rs.getString("account_number"),
            rs.getString("holder_name"), rs.getString("added_at"));
    private static final RowMapper<Transaction> TRANSACTION = (rs, i) -> new Transaction(rs.getString("transaction_id"),
            rs.getString("created_at"), rs.getString("from_account"), rs.getString("to_account"),
            rs.getBigDecimal("amount"), rs.getString("initiated_by"), rs.getString("note"));

    public BankRepository(JdbcTemplate jdbc, TransactionTemplate tx, PasswordEncoder encoder,
                          @Value("${banking.seed.default-password}") String seedPassword,
                          @Value("${banking.import.csv-dir:./data}") String importDir) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.encoder = encoder;
        this.seedPassword = seedPassword;
        this.importDir = importDir;
    }

    /** First start against an empty database: import ./data/*.csv if present, otherwise create sample data. */
    @PostConstruct
    public void initData() {
        if (count("bank.accounts") > 0) {
            log.info("Database has {} customers, {} accounts, {} transactions",
                    count("bank.customers"), count("bank.accounts"), count("bank.transactions"));
            return;
        }
        try {
            Path dir = importDir == null || importDir.isBlank() ? null : Path.of(importDir);
            if (dir != null && Files.exists(dir.resolve("Accounts.csv"))) importCsv(dir);
            else seed();
        } catch (DuplicateKeyException e) {
            log.info("Another server instance is initialising the database at the same time; using its data");
        }
    }

    private void importCsv(Path dir) {
        CsvTableStore csv = new CsvTableStore(dir);
        tx.executeWithoutResult(s -> {
            csv.readAll("Customers").forEach(r -> insertCustomer(Customer.fromRow(r)));
            csv.readAll("Accounts").forEach(r -> insertAccount(Account.fromRow(r)));
            csv.readAll("Beneficiaries").forEach(r -> insertBeneficiary(Beneficiary.fromRow(r)));
            csv.readAll("Transactions").forEach(r -> insertTransaction(Transaction.fromRow(r)));
        });
        log.info("Imported existing data from {}: {} customers, {} accounts, {} transactions",
                dir.toAbsolutePath(), count("bank.customers"), count("bank.accounts"), count("bank.transactions"));
    }

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
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < sample.length; i++) {
                String[] r = sample[i];
                String custId = String.format("CUST%03d", i + 1);
                insertCustomer(new Customer(custId, r[0], r[1], r[2], hash, now));
                insertAccount(new Account(String.valueOf(1000000001L + i), custId, r[0], r[3], new BigDecimal(r[4]), now));
            }
        });
        log.info("Seeded 10 sample accounts (password for all sample customers: {})", seedPassword);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    // ---------- customers ----------

    public Optional<Customer> findCustomer(String id) {
        return jdbc.query("SELECT * FROM bank.customers WHERE customer_id = ?", CUSTOMER, id).stream().findFirst();
    }

    public Optional<Customer> findCustomerByEmail(String email) {
        return jdbc.query("SELECT * FROM bank.customers WHERE LOWER(email) = LOWER(?)", CUSTOMER, email).stream().findFirst();
    }

    public void insertCustomer(Customer c) {
        jdbc.update("INSERT INTO bank.customers (customer_id, full_name, email, phone, password_hash, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", c.customerId(), c.fullName(), c.email(), c.phone(), c.passwordHash(), c.createdAt());
    }

    public String nextCustomerId() {
        int max = jdbc.queryForList("SELECT customer_id FROM bank.customers", String.class).stream()
                .filter(id -> id.matches("CUST\\d+")).mapToInt(id -> Integer.parseInt(id.substring(4))).max().orElse(0);
        return String.format("CUST%03d", max + 1);
    }

    // ---------- accounts ----------

    public List<Account> allAccounts() {
        return jdbc.query("SELECT * FROM bank.accounts ORDER BY account_number", ACCOUNT);
    }

    public Optional<Account> findAccount(String accountNumber) {
        return jdbc.query("SELECT * FROM bank.accounts WHERE account_number = ?", ACCOUNT, accountNumber).stream().findFirst();
    }

    /** Locks the row until the surrounding transaction ends, so concurrent transfers queue up. */
    public Optional<Account> findAccountForUpdate(String accountNumber) {
        return jdbc.query("SELECT * FROM bank.accounts WHERE account_number = ? FOR UPDATE", ACCOUNT, accountNumber)
                .stream().findFirst();
    }

    public List<Account> accountsOf(String customerId) {
        return jdbc.query("SELECT * FROM bank.accounts WHERE customer_id = ? ORDER BY account_number", ACCOUNT, customerId);
    }

    public void insertAccount(Account a) {
        jdbc.update("INSERT INTO bank.accounts (account_number, customer_id, holder_name, account_type, balance, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", a.accountNumber(), a.customerId(), a.holderName(), a.accountType(), a.balance(), a.createdAt());
    }

    public void addToBalance(String accountNumber, BigDecimal delta) {
        int n = jdbc.update("UPDATE bank.accounts SET balance = balance + ? WHERE account_number = ?", delta, accountNumber);
        if (n != 1) throw new IllegalStateException("Account " + accountNumber + " not updated");
    }

    public String nextAccountNumber() {
        String max = jdbc.queryForObject("SELECT MAX(account_number) FROM bank.accounts", String.class);
        return String.valueOf((max == null ? 1000000000L : Long.parseLong(max)) + 1);
    }

    // ---------- beneficiaries ----------

    public List<Beneficiary> beneficiariesOf(String customerId) {
        return jdbc.query("SELECT * FROM bank.beneficiaries WHERE customer_id = ? ORDER BY added_at", BENEFICIARY, customerId);
    }

    public Optional<Beneficiary> findBeneficiary(String id) {
        return jdbc.query("SELECT * FROM bank.beneficiaries WHERE beneficiary_id = ?", BENEFICIARY, id).stream().findFirst();
    }

    public void insertBeneficiary(Beneficiary b) {
        jdbc.update("INSERT INTO bank.beneficiaries (beneficiary_id, customer_id, nickname, account_number, holder_name, added_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", b.beneficiaryId(), b.customerId(), b.nickname(), b.accountNumber(), b.holderName(), b.addedAt());
    }

    public boolean deleteBeneficiary(String id, String customerId) {
        return jdbc.update("DELETE FROM bank.beneficiaries WHERE beneficiary_id = ? AND customer_id = ?", id, customerId) == 1;
    }

    // ---------- transactions ----------

    public void insertTransaction(Transaction t) {
        jdbc.update("INSERT INTO bank.transactions (transaction_id, created_at, from_account, to_account, amount, initiated_by, note) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", t.transactionId(), t.timestamp(), t.fromAccount(), t.toAccount(), t.amount(),
                t.initiatedBy(), t.note() == null ? "" : t.note());
    }

    public List<Transaction> recentTransactions(int limit) {
        return jdbc.query("SELECT * FROM bank.transactions ORDER BY seq DESC LIMIT ?", TRANSACTION, limit);
    }

    public List<Transaction> transactionsFor(Set<String> accountNumbers, int limit) {
        if (accountNumbers.isEmpty()) return List.of();
        String in = accountNumbers.stream().map(a -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(accountNumbers);
        args.addAll(accountNumbers);
        args.add(limit);
        return jdbc.query("SELECT * FROM bank.transactions WHERE from_account IN (" + in + ") OR to_account IN (" + in + ") "
                + "ORDER BY seq DESC LIMIT ?", TRANSACTION, args.toArray());
    }
}
