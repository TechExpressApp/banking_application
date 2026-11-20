package com.bank.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Chooses the database:
 * <ul>
 *   <li>{@code banking.database.url} set (POSTGRES_URL / DATABASE_URL) → PostgreSQL, e.g. Supabase</li>
 *   <li>otherwise → a local H2 file database in ./data (PostgreSQL compatibility mode), no setup needed</li>
 * </ul>
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean(destroyMethod = "close")
    public DataSource dataSource(@Value("${banking.database.url:}") String url,
                                 @Value("${banking.database.local-path:./data/bankdb}") String localPath,
                                 @Value("${banking.database.require-external:false}") boolean requireExternal,
                                 @Value("${banking.database.pool-size:4}") int poolSize) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("bank-db");
        c.setMaximumPoolSize(poolSize);
        if (url != null && !url.isBlank()) {
            PostgresUrl pg = PostgresUrl.parse(url);
            c.setJdbcUrl(pg.jdbcUrl());
            c.setUsername(pg.user());
            c.setPassword(pg.password());
            log.info("Database: PostgreSQL at {}", pg.describe());
        } else {
            if (requireExternal) {
                throw new IllegalStateException("No database configured. Set POSTGRES_URL (or DATABASE_URL) to your "
                        + "Supabase/PostgreSQL connection string. Local files are not kept on this host.");
            }
            c.setJdbcUrl("jdbc:h2:file:" + localPath
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;LOCK_TIMEOUT=10000");
            c.setUsername("sa");
            log.info("Database: local H2 file {}.mv.db (set POSTGRES_URL to use Supabase)", localPath);
        }
        return new HikariDataSource(c);
    }

    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }
}
