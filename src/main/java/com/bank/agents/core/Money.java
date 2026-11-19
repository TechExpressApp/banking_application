package com.bank.agents.core;

import java.math.BigDecimal;

public final class Money {
    private Money() {}

    /** Parses a model-supplied amount such as "1500", "1,500.50" or "₹ 200". */
    public static BigDecimal parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("amount is required");
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        try {
            BigDecimal v = new BigDecimal(cleaned);
            if (v.signum() <= 0) throw new IllegalArgumentException("amount must be greater than zero");
            if (v.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("amount can have at most 2 decimal places");
            return v.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a valid amount");
        }
    }
}
