package com.bank.agents.fraud;

import com.bank.service.BankException;
import com.bank.store.TableStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Fraud alerts, saved to the "FraudAlerts" tab/file next to the rest of the bank data. */
@Component
public class FraudAlertStore {

    public static final String TABLE = "FraudAlerts";
    private final TableStore store;
    private final List<FraudAlert> alerts = new ArrayList<>();

    public FraudAlertStore(TableStore store) {
        this.store = store;
    }

    @PostConstruct
    synchronized void load() {
        store.readAll(TABLE).forEach(r -> alerts.add(FraudAlert.fromRow(r)));
    }

    public synchronized void add(FraudAlert a) {
        alerts.add(a);
        flush();
    }

    /** Newest first; status null = all. */
    public synchronized List<FraudAlert> list(String status, int limit) {
        return alerts.reversed().stream()
                .filter(a -> status == null || status.isBlank() || a.status().equalsIgnoreCase(status))
                .limit(limit).toList();
    }

    public synchronized FraudAlert resolve(String alertId, String note) {
        for (int i = 0; i < alerts.size(); i++) {
            if (alerts.get(i).alertId().equals(alertId)) {
                FraudAlert r = alerts.get(i).resolve(note);
                alerts.set(i, r);
                flush();
                return r;
            }
        }
        throw BankException.notFound("Fraud alert " + alertId + " not found");
    }

    private void flush() {
        store.writeAll(TABLE, FraudAlert.HEADER, alerts.stream().map(FraudAlert::toRow).toList());
    }
}
