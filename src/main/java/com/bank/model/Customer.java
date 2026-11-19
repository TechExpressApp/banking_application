package com.bank.model;

import java.util.List;

public record Customer(String customerId, String fullName, String email, String phone,
                       String passwordHash, String createdAt) {

    public static final List<String> HEADER =
            List.of("customerId", "fullName", "email", "phone", "passwordHash", "createdAt");

    public List<String> toRow() {
        return List.of(customerId, fullName, email, phone, passwordHash, createdAt);
    }

    public static Customer fromRow(List<String> r) {
        return new Customer(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5));
    }
}
