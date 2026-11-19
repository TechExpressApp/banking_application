package com.bank.agents.operations;

import com.bank.agents.core.*;
import com.bank.agents.fraud.FraudAlertStore;
import com.bank.model.Account;
import com.bank.model.Transaction;
import com.bank.service.BankService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.bank.agents.core.ToolSchema.*;

/**
 * Assistant for bank staff: searches accounts, reports on activity, works the fraud-alert queue, and
 * PROPOSES bank-wide transfers that a staff member must confirm.
 */
@Component
public class OperationsAgent {

    public static final String NAME = "operations";
    public static final String TRANSFER_KIND = "BANK_TRANSFER";

    private final AgentDefinition definition;

    public OperationsAgent(BankService bank, FraudAlertStore alerts) {
        List<AgentTool> tools = List.of(
                FunctionTool.of(tool("search_accounts",
                        "Find accounts by holder name (partial, case-insensitive) or account number. Empty query lists all.")
                                .string("query", "Name or account number fragment", true),
                        (in, ctx) -> {
                            String q = Objects.requireNonNullElse(str(in, "query"), "").toLowerCase();
                            return bank.allAccounts().stream()
                                    .filter(a -> q.isEmpty() || a.holderName().toLowerCase().contains(q) || a.accountNumber().contains(q))
                                    .limit(50).toList();
                        }),

                FunctionTool.of(tool("get_account",
                        "Details of one account plus its most recent transactions.")
                                .string("account_number", "10-digit account number", true)
                                .integer("limit", "How many transactions (1-50, default 10)", false),
                        (in, ctx) -> {
                            String no = str(in, "account_number");
                            return Map.of("account", bank.account(no),
                                    "recentTransactions", bank.transactionsOfAccount(no, intOr(in, "limit", 10, 50)));
                        }),

                FunctionTool.of(tool("list_recent_transactions",
                        "Most recent transactions across the whole bank, newest first.")
                                .integer("limit", "How many (1-100, default 20)", false),
                        (in, ctx) -> bank.recentTransactions(intOr(in, "limit", 20, 100))),

                FunctionTool.of(tool("bank_summary",
                        "Totals for the whole bank: number of accounts, total and average balance, "
                                + "largest and smallest balances, and transfer volume in the recent history."),
                        (in, ctx) -> summary(bank)),

                FunctionTool.of(tool("list_fraud_alerts",
                        "Fraud alerts raised by the fraud monitor, newest first.")
                                .string("status", "OPEN, RESOLVED, or empty for all", false)
                                .integer("limit", "How many (1-50, default 20)", false),
                        (in, ctx) -> alerts.list(str(in, "status"), intOr(in, "limit", 20, 50))),

                FunctionTool.of(tool("resolve_fraud_alert",
                        "Mark a fraud alert as resolved, with a short note on what was found. "
                                + "Only call this when the staff member asks to resolve it.")
                                .string("alert_id", "Alert id, e.g. FA-1A2B3C4D", true)
                                .string("note", "What was checked / the outcome", true),
                        (in, ctx) -> alerts.resolve(str(in, "alert_id"), str(in, "note"))),

                FunctionTool.of(tool("request_bank_transfer",
                        "Prepare a transfer between ANY two accounts in the bank. This does NOT move money: it "
                                + "creates a pending transfer that the staff member must confirm in the app.")
                                .string("from_account", "Source account number", true)
                                .string("to_account", "Target account number", true)
                                .string("amount", "Amount as a decimal string, e.g. \"1500.00\"", true)
                                .string("note", "Reason for the transfer", true),
                        (in, ctx) -> requestTransfer(bank, in, ctx))
        );
        this.definition = new AgentDefinition(NAME, PromptLoader.load("operations/system-prompt.md"), tools);
    }

    private static Object requestTransfer(BankService bank, Map<String, Object> in, ToolContext ctx) {
        String from = str(in, "from_account"), to = str(in, "to_account"), note = str(in, "note");
        BigDecimal amount = Money.parse(str(in, "amount"));
        if (from.equals(to)) throw new IllegalArgumentException("Source and target must be different accounts");
        Account src = bank.account(from), dst = bank.account(to);
        if (src.balance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds: " + from + " has " + src.balance());
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("From", src.holderName() + " · " + from);
        details.put("To", dst.holderName() + " · " + to);
        details.put("Amount", amount.toPlainString());
        details.put("Note", note);
        PendingAction a = ctx.pendingActions().create(TRANSFER_KIND, ctx,
                "Transfer " + amount.toPlainString() + " from " + src.holderName() + " to " + dst.holderName(), details,
                () -> bank.transfer(from, to, amount, "OPS-AGENT", note));
        return Map.of("status", "PENDING_CONFIRMATION", "pending_action_id", a.id(),
                "message", "Transfer prepared; the staff member must press Confirm within 5 minutes. No money has moved yet.");
    }

    private static Map<String, Object> summary(BankService bank) {
        List<Account> accounts = bank.allAccounts();
        BigDecimal total = accounts.stream().map(Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Transaction> txns = bank.recentTransactions(1000);
        BigDecimal volume = txns.stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Comparator<Account> byBal = Comparator.comparing(Account::balance);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", accounts.size());
        m.put("totalBalance", total);
        m.put("averageBalance", accounts.isEmpty() ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(accounts.size()), 2, java.math.RoundingMode.HALF_UP));
        accounts.stream().max(byBal).ifPresent(a -> m.put("largest", a));
        accounts.stream().min(byBal).ifPresent(a -> m.put("smallest", a));
        m.put("transactionsInHistory", txns.size());
        m.put("transferVolume", volume);
        return m;
    }

    public AgentDefinition definition() {
        return definition;
    }
}
