package com.bank;

import com.bank.agents.fraud.FraudAlertStore;
import com.bank.repo.BankRepository;
import com.bank.service.BankService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/** A fresh in-memory database (H2, PostgreSQL mode) with the real schema, plus the bank objects on top of it. */
public final class TestBank {

    public final DataSource dataSource;
    public final JdbcTemplate jdbc;
    public final BankRepository repo;
    public final BankService bank;
    public final FraudAlertStore alerts;

    private TestBank(DataSource ds) {
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var encoder = new BCryptPasswordEncoder(4);
        this.repo = new BankRepository(jdbc, tx, encoder, "Password@123", "");
        repo.initData();
        this.bank = new BankService(repo, encoder, e -> { }, tx);
        this.alerts = new FraudAlertStore(jdbc, "");
    }

    /** New, empty database seeded with the 10 sample accounts. */
    public static TestBank create() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new TestBank(ds);
    }

    /** A second "server instance" sharing the same database. */
    public TestBank anotherInstance() {
        return new TestBank(dataSource);
    }
}
