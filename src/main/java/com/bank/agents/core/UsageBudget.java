package com.bank.agents.core;

import com.anthropic.models.messages.Usage;
import com.bank.service.BankException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Daily spending cap for the Claude API. Every call's cost is estimated from the token counts the API returns
 * and added to today's total in the bank.agent_usage table, which all server instances share. Once the total
 * reaches the budget, agent calls are refused until midnight in the configured time zone.
 *
 * <p>The check happens before each call and the cost is only known after it, so the day can end slightly
 * over budget by at most the cost of the calls already in progress.
 */
@Component
public class UsageBudget {

    private static final Logger log = LoggerFactory.getLogger(UsageBudget.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** USD per million tokens. Cache writes are priced at 1.25x input, cache reads at 0.1x (an upper bound). */
    record Price(double input, double output) {}

    private final JdbcTemplate jdbc;
    private final BigDecimal dailyBudget;
    private final ZoneId zone;

    public UsageBudget(JdbcTemplate jdbc,
                       @Value("${banking.agents.daily-budget-usd:1.00}") BigDecimal dailyBudget,
                       @Value("${banking.agents.budget-zone:UTC}") String zone) {
        this.jdbc = jdbc;
        this.dailyBudget = dailyBudget;
        this.zone = ZoneId.of(zone);
    }

    static Price priceFor(String model) {
        String m = model == null ? "" : model;
        if (m.startsWith("claude-haiku-5")) return new Price(0.10, 0.50);
        if (m.startsWith("claude-haiku-4")) return new Price(1, 5);
        if (m.startsWith("claude-sonnet-5")) return new Price(2, 10);
        if (m.startsWith("claude-sonnet-4")) return new Price(3, 15);
        if (m.startsWith("claude-opus-5-5")) return new Price(4, 20);
        if (m.startsWith("claude-opus")) return new Price(5, 25);
        if (m.startsWith("claude-fable-5-2") || m.startsWith("claude-mythos-5-2")) return new Price(8, 40);
        return new Price(10, 50);                       // unknown model: assume the most expensive
    }

    /** Estimated cost in USD of one API response. */
    public static BigDecimal cost(String model, Usage u) {
        return cost(model, u.inputTokens(), u.outputTokens(),
                u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L));
    }

    static BigDecimal cost(String model, long input, long output, long cacheWrite, long cacheRead) {
        Price p = priceFor(model);
        double usd = (input * p.input() + cacheWrite * p.input() * 1.25 + cacheRead * p.input() * 0.1 + output * p.output());
        return BigDecimal.valueOf(usd).divide(MILLION, 6, RoundingMode.UP);
    }

    private String today() {
        return LocalDate.now(zone).toString();
    }

    public BigDecimal spentToday() {
        BigDecimal v = jdbc.query("SELECT cost_usd FROM bank.agent_usage WHERE usage_day = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, today());
        return v == null ? BigDecimal.ZERO : v;
    }

    public boolean hasBudget() {
        return spentToday().compareTo(dailyBudget) < 0;
    }

    /** Throws HTTP 429 when today's budget is used up. */
    public void requireBudget() {
        if (!hasBudget()) {
            throw new BankException(HttpStatus.TOO_MANY_REQUESTS, "Today's AI budget ($" + dailyBudget.setScale(2, RoundingMode.HALF_UP)
                    + ") has been used up. The assistants will be available again after midnight (" + zone + ").");
        }
    }

    /** Adds one API call's cost to today's total. Never throws: a bookkeeping error must not break a chat. */
    public void record(String model, Usage usage) {
        try {
            add(cost(model, usage));
        } catch (Exception e) {
            log.warn("Could not record AI usage: {}", e.getMessage());
        }
    }

    void add(BigDecimal cost) {
        String day = today();
        String update = "UPDATE bank.agent_usage SET cost_usd = cost_usd + ?, calls = calls + 1 WHERE usage_day = ?";
        if (jdbc.update(update, cost, day) == 1) return;
        try {
            jdbc.update("INSERT INTO bank.agent_usage (usage_day, cost_usd, calls) VALUES (?, ?, 1)", day, cost);
        } catch (DuplicateKeyException e) {
            jdbc.update(update, cost, day);              // another instance created today's row first
        }
    }

    public Map<String, Object> status() {
        BigDecimal spent = spentToday();
        Integer calls = jdbc.query("SELECT calls FROM bank.agent_usage WHERE usage_day = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, today());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", today());
        m.put("timeZone", zone.toString());
        m.put("dailyBudgetUsd", dailyBudget);
        m.put("spentTodayUsd", spent.setScale(4, RoundingMode.HALF_UP));
        m.put("callsToday", calls);
        m.put("remainingUsd", dailyBudget.subtract(spent).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP));
        return m;
    }
}
