package com.bank.agents.fraud;

import com.bank.service.BankException;
import com.bank.store.CsvTableStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fraud alerts in the bank.fraud_alerts table. */
@Component
public class FraudAlertStore {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertStore.class);

    private static final RowMapper<FraudAlert> ALERT = (rs, i) -> new FraudAlert(rs.getString("alert_id"),
            rs.getString("created_at"), rs.getString("transaction_id"), rs.getString("from_account"),
            rs.getString("to_account"), rs.getString("amount"), rs.getString("risk_level"), rs.getInt("risk_score"),
            rs.getString("reasons"), rs.getString("recommended_action"), rs.getString("triggers"),
            rs.getString("status"), rs.getString("resolved_note"));

    private final JdbcTemplate jdbc;
    private final String importDir;

    public FraudAlertStore(JdbcTemplate jdbc, @Value("${banking.import.csv-dir:./data}") String importDir) {
        this.jdbc = jdbc;
        this.importDir = importDir;
    }

    /** Imports ./data/FraudAlerts.csv once, when the table is empty. */
    @PostConstruct
    void importOnce() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM bank.fraud_alerts", Integer.class);
        if (n != null && n > 0 || importDir == null || importDir.isBlank()) return;
        Path dir = Path.of(importDir);
        if (!Files.exists(dir.resolve("FraudAlerts.csv"))) return;
        try {
            List<FraudAlert> rows = new CsvTableStore(dir).readAll("FraudAlerts").stream().map(FraudAlert::fromRow).toList();
            rows.forEach(this::add);
            log.info("Imported {} fraud alerts from {}", rows.size(), dir.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not import fraud alerts: {}", e.getMessage());
        }
    }

    public void add(FraudAlert a) {
        jdbc.update("INSERT INTO bank.fraud_alerts (alert_id, created_at, transaction_id, from_account, to_account, amount, "
                        + "risk_level, risk_score, reasons, recommended_action, triggers, status, resolved_note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.alertId(), a.createdAt(), a.transactionId(), a.fromAccount(), a.toAccount(), a.amount(), a.riskLevel(),
                a.riskScore(), a.reasons(), a.recommendedAction() == null ? "" : a.recommendedAction(), a.triggers(),
                a.status(), a.resolvedNote() == null ? "" : a.resolvedNote());
    }

    /** Newest first; status null/blank = all. */
    public List<FraudAlert> list(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM bank.fraud_alerts ORDER BY seq DESC LIMIT ?", ALERT, limit);
        }
        return jdbc.query("SELECT * FROM bank.fraud_alerts WHERE UPPER(status) = UPPER(?) ORDER BY seq DESC LIMIT ?",
                ALERT, status, limit);
    }

    public FraudAlert resolve(String alertId, String note) {
        int n = jdbc.update("UPDATE bank.fraud_alerts SET status = 'RESOLVED', resolved_note = ? WHERE alert_id = ?",
                note == null ? "" : note, alertId);
        if (n == 0) throw BankException.notFound("Fraud alert " + alertId + " not found");
        return jdbc.query("SELECT * FROM bank.fraud_alerts WHERE alert_id = ?", ALERT, alertId).getFirst();
    }
}
