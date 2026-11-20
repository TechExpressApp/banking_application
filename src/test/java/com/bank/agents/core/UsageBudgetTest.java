package com.bank.agents.core;

import com.bank.TestBank;
import com.bank.service.BankException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class UsageBudgetTest {

    @Test
    void pricesTokensPerModel() {
        // Opus 5.5: $4 in / $20 out per million tokens
        assertEquals(new BigDecimal("4.000000"), UsageBudget.cost("claude-opus-5-5", 1_000_000, 0, 0, 0));
        assertEquals(new BigDecimal("20.000000"), UsageBudget.cost("claude-opus-5-5", 0, 1_000_000, 0, 0));
        // typical chat turn: 3,000 cached prompt tokens read, 200 new input, 300 output
        BigDecimal turn = UsageBudget.cost("claude-opus-5-5", 200, 300, 0, 3000);
        assertEquals(new BigDecimal("0.008000"), turn);                 // under one cent
        // unknown models are priced at the most expensive rate, never under-counted
        assertTrue(UsageBudget.cost("some-new-model", 1000, 1000, 0, 0)
                .compareTo(UsageBudget.cost("claude-opus-5-5", 1000, 1000, 0, 0)) > 0);
    }

    @Test
    void blocksOnceTheDailyBudgetIsReachedAcrossInstances() {
        TestBank tb = TestBank.create();
        UsageBudget a = new UsageBudget(tb.jdbc, new BigDecimal("1.00"), "America/New_York");
        UsageBudget b = new UsageBudget(tb.jdbc, new BigDecimal("1.00"), "America/New_York");   // second instance

        a.add(new BigDecimal("0.60"));
        assertTrue(b.hasBudget());
        b.add(new BigDecimal("0.39"));
        assertTrue(a.hasBudget());                                       // $0.99 spent
        a.add(new BigDecimal("0.02"));                                   // $1.01
        assertFalse(b.hasBudget());
        BankException e = assertThrows(BankException.class, a::requireBudget);
        assertEquals(429, e.status().value());
        assertEquals(3, b.status().get("callsToday"));
        assertEquals(new BigDecimal("0.0000"), b.status().get("remainingUsd"));
    }
}
