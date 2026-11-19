package com.bank.model;

import java.math.BigDecimal;
import java.util.List;

public record Transaction(String transactionId, String timestamp, String fromAccount, String toAccount,
                          BigDecimal amount, String initiatedBy, String note) {

    public static final List<String> HEADER =
            List.of("transactionId", "timestamp", "fromAccount", "toAccount", "amount", "initiatedBy", "note");

    public List<String> toRow() {
        return List.of(transactionId, timestamp, fromAccount, toAccount, amount.toPlainString(), initiatedBy, note);
    }

    public static Transaction fromRow(List<String> r) {
        return new Transaction(r.get(0), r.get(1), r.get(2), r.get(3), new BigDecimal(r.get(4)), r.get(5),
                r.size() > 6 ? r.get(6) : "");
    }
}
