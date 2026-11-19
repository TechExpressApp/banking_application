package com.bank.model;

import java.math.BigDecimal;
import java.util.List;

public record Account(String accountNumber, String customerId, String holderName,
                      String accountType, BigDecimal balance, String createdAt) {

    public static final List<String> HEADER =
            List.of("accountNumber", "customerId", "holderName", "accountType", "balance", "createdAt");

    public Account withBalance(BigDecimal newBalance) {
        return new Account(accountNumber, customerId, holderName, accountType, newBalance, createdAt);
    }

    public List<String> toRow() {
        return List.of(accountNumber, customerId, holderName, accountType, balance.toPlainString(), createdAt);
    }

    public static Account fromRow(List<String> r) {
        return new Account(r.get(0), r.get(1), r.get(2), r.get(3), new BigDecimal(r.get(4)), r.get(5));
    }
}
