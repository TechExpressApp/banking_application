package com.bank.agents.assistant;

import com.bank.agents.core.*;
import com.bank.model.Account;
import com.bank.model.Beneficiary;
import com.bank.service.BankService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.bank.agents.core.ToolSchema.*;

/**
 * Chat assistant for a logged-in customer. Can read the customer's own accounts, history and beneficiaries,
 * add beneficiaries, and PROPOSE transfers. Transfers only run after the customer presses Confirm.
 */
@Component
public class BankingAssistantAgent {

    public static final String NAME = "assistant";
    public static final String TRANSFER_KIND = "CUSTOMER_TRANSFER";

    private final AgentDefinition definition;

    public BankingAssistantAgent(BankService bank) {
        List<AgentTool> tools = List.of(
                FunctionTool.of(tool("get_my_accounts",
                        "List the customer's own accounts with account number, type and current balance."),
                        (in, ctx) -> bank.accountsOf(ctx.requireCustomer())),

                FunctionTool.of(tool("get_my_transactions",
                        "Recent transactions on the customer's accounts, newest first.")
                                .integer("limit", "How many to return (1-50, default 10)", false),
                        (in, ctx) -> bank.transactionsOf(ctx.requireCustomer(), intOr(in, "limit", 10, 50))),

                FunctionTool.of(tool("list_beneficiaries",
                        "List the customer's saved beneficiaries (people they can send money to), with beneficiary_id."),
                        (in, ctx) -> bank.beneficiaries(ctx.requireCustomer())),

                FunctionTool.of(tool("add_beneficiary",
                        "Save another customer of this bank as a beneficiary, by their 10-digit account number. "
                                + "Only call this when the customer has given the account number.")
                                .string("account_number", "10-digit account number at this bank", true)
                                .string("nickname", "Optional friendly name, e.g. 'Mom'", false),
                        (in, ctx) -> {
                            Beneficiary b = bank.addBeneficiary(ctx.requireCustomer(), str(in, "account_number"), str(in, "nickname"));
                            return Map.of("added", true, "beneficiary_id", b.beneficiaryId(), "nickname", b.nickname(),
                                    "holder_name", b.holderName(), "account_number", b.accountNumber());
                        }),

                FunctionTool.of(tool("request_transfer",
                        "Prepare a transfer from one of the customer's accounts to one of their beneficiaries. "
                                + "This does NOT move money: it creates a pending transfer that the customer must "
                                + "confirm with the Confirm button in the app.")
                                .string("from_account", "The customer's own account number to send from", true)
                                .string("beneficiary_id", "beneficiary_id from list_beneficiaries", true)
                                .string("amount", "Amount as a decimal string, e.g. \"1500.00\"", true)
                                .string("note", "Optional note for the transfer", false),
                        (in, ctx) -> requestTransfer(bank, in, ctx))
        );
        this.definition = new AgentDefinition(NAME, PromptLoader.load("assistant/system-prompt.md"), tools);
    }

    private static Object requestTransfer(BankService bank, Map<String, Object> in, ToolContext ctx) {
        String customerId = ctx.requireCustomer();
        String from = str(in, "from_account");
        BigDecimal amount = Money.parse(str(in, "amount"));
        String note = str(in, "note");

        Account source = bank.accountsOf(customerId).stream()
                .filter(a -> a.accountNumber().equals(from)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account " + from + " is not one of the customer's accounts"));
        Beneficiary ben = bank.beneficiary(customerId, str(in, "beneficiary_id"));
        if (source.balance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient funds: account " + from + " has " + source.balance());
        }

        Map<String, String> details = new LinkedHashMap<>();
        details.put("From", from);
        details.put("To", ben.nickname() + " (" + ben.holderName() + ")");
        details.put("To account", ben.accountNumber());
        details.put("Amount", amount.toPlainString());
        if (note != null && !note.isBlank()) details.put("Note", note);

        PendingAction a = ctx.pendingActions().create(TRANSFER_KIND, ctx,
                "Send " + amount.toPlainString() + " to " + ben.nickname(), details,
                () -> bank.transferToBeneficiary(customerId, from, ben.beneficiaryId(), amount, note));
        return Map.of("status", "PENDING_CONFIRMATION", "pending_action_id", a.id(),
                "message", "Transfer prepared. The customer must press Confirm in the app within 5 minutes; no money has moved yet.");
    }

    public AgentDefinition definition() {
        return definition;
    }
}
