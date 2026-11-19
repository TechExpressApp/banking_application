package com.bank.agents.fraud;

import java.util.List;

public record FraudAlert(String alertId, String createdAt, String transactionId, String fromAccount, String toAccount,
                         String amount, String riskLevel, int riskScore, String reasons, String recommendedAction,
                         String triggers, String status, String resolvedNote) {

    public static final List<String> HEADER = List.of("alertId", "createdAt", "transactionId", "fromAccount", "toAccount",
            "amount", "riskLevel", "riskScore", "reasons", "recommendedAction", "triggers", "status", "resolvedNote");

    public List<String> toRow() {
        return List.of(alertId, createdAt, transactionId, fromAccount, toAccount, amount, riskLevel,
                String.valueOf(riskScore), reasons, recommendedAction, triggers, status, resolvedNote);
    }

    public static FraudAlert fromRow(List<String> r) {
        return new FraudAlert(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6),
                Integer.parseInt(r.get(7)), r.get(8), r.get(9), r.get(10), r.get(11), r.size() > 12 ? r.get(12) : "");
    }

    public FraudAlert resolve(String note) {
        return new FraudAlert(alertId, createdAt, transactionId, fromAccount, toAccount, amount, riskLevel, riskScore,
                reasons, recommendedAction, triggers, "RESOLVED", note == null ? "" : note);
    }
}
