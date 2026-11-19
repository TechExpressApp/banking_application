package com.bank.model;

import java.util.List;

public record Beneficiary(String beneficiaryId, String customerId, String nickname,
                          String accountNumber, String holderName, String addedAt) {

    public static final List<String> HEADER =
            List.of("beneficiaryId", "customerId", "nickname", "accountNumber", "holderName", "addedAt");

    public List<String> toRow() {
        return List.of(beneficiaryId, customerId, nickname, accountNumber, holderName, addedAt);
    }

    public static Beneficiary fromRow(List<String> r) {
        return new Beneficiary(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5));
    }
}
