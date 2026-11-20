package com.bank.agents;

import com.bank.agents.assistant.BankingAssistantAgent;
import com.bank.agents.core.*;
import com.bank.TestBank;
import com.bank.agents.fraud.FraudMonitorAgent;
import com.bank.agents.operations.OperationsAgent;
import com.bank.model.Transaction;
import com.bank.repo.BankRepository;
import com.bank.service.BankException;
import com.bank.service.BankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the agents' tools and safety rules directly, without calling the Claude API. */
class AgentToolsTest {

    BankRepository repo;
    BankService bank;
    PendingActionStore pending;
    AgentDefinition assistant, operations;
    FraudMonitorAgent fraud;
    ToolContext aarav;

    @BeforeEach
    void setUp() {
        TestBank tb = TestBank.create();
        repo = tb.repo;
        bank = tb.bank;
        pending = new PendingActionStore();
        assistant = new BankingAssistantAgent(bank).definition();
        operations = new OperationsAgent(bank, tb.alerts).definition();
        fraud = new FraudMonitorAgent(null, new AgentProperties(), bank, tb.alerts, new UsageBudget(tb.jdbc, new java.math.BigDecimal("1.00"), "UTC"));
        aarav = new ToolContext("CUST:CUST001", "CUST001", "conv-1", pending);
    }

    private String run(AgentDefinition agent, String tool, Map<String, Object> in, ToolContext ctx) throws Exception {
        return agent.tool(tool).execute(in, ctx);
    }

    private BigDecimal balance(String acc) { return repo.findAccount(acc).orElseThrow().balance(); }

    @Test
    void toolDefinitionsAreStrict() {
        assistant.tools().forEach(t -> assertTrue(t.definition().strict().orElse(false), t.name()));
        operations.tools().forEach(t -> assertTrue(t.definition().strict().orElse(false), t.name()));
    }

    @Test
    void assistantOnlySeesOwnAccounts() throws Exception {
        String out = run(assistant, "get_my_accounts", Map.of(), aarav);
        assertTrue(out.contains("1000000001"));
        assertFalse(out.contains("1000000002"));
    }

    @Test
    void transferNeedsConfirmationAndRunsOnce() throws Exception {
        String ben = run(assistant, "add_beneficiary", Map.of("account_number", "1000000002", "nickname", "Priya"), aarav);
        String benId = ben.replaceAll(".*\"beneficiary_id\":\"([^\"]+)\".*", "$1");

        String out = run(assistant, "request_transfer",
                Map.of("from_account", "1000000001", "beneficiary_id", benId, "amount", "1,500.00"), aarav);
        assertTrue(out.contains("PENDING_CONFIRMATION"));
        assertEquals(new BigDecimal("25000.00"), balance("1000000001"), "no money moves before confirmation");

        String actionId = pending.forConversation("conv-1").getFirst().id();
        assertThrows(BankException.class, () -> pending.confirm(actionId, "CUST:CUST002"), "someone else can't confirm");
        Object result = pending.confirm(actionId, "CUST:CUST001");
        assertInstanceOf(Transaction.class, result);
        assertEquals(new BigDecimal("23500.00"), balance("1000000001"));
        assertEquals(new BigDecimal("20000.50"), balance("1000000002"));
        assertThrows(BankException.class, () -> pending.confirm(actionId, "CUST:CUST001"), "can't confirm twice");
    }

    @Test
    void assistantCannotSpendFromAnotherCustomersAccount() throws Exception {
        String ben = run(assistant, "add_beneficiary", Map.of("account_number", "1000000003"), aarav);
        String benId = ben.replaceAll(".*\"beneficiary_id\":\"([^\"]+)\".*", "$1");
        assertThrows(IllegalArgumentException.class, () -> run(assistant, "request_transfer",
                Map.of("from_account", "1000000005", "beneficiary_id", benId, "amount", "10"), aarav));
        assertThrows(IllegalArgumentException.class, () -> run(assistant, "request_transfer",
                Map.of("from_account", "1000000001", "beneficiary_id", benId, "amount", "999999"), aarav));
        assertTrue(pending.forConversation("conv-1").isEmpty());
    }

    @Test
    void customerToolsRefuseWithoutCustomer() {
        ToolContext staff = new ToolContext("STAFF", null, "conv-2", pending);
        assertThrows(IllegalStateException.class, () -> run(assistant, "get_my_accounts", Map.of(), staff));
    }

    @Test
    void operationsToolsWork() throws Exception {
        assertTrue(run(operations, "search_accounts", Map.of("query", "vikram"), null).contains("1000000005"));
        assertTrue(run(operations, "bank_summary", Map.of(), null).contains("\"accounts\":10"));
        ToolContext staff = new ToolContext("STAFF", null, "conv-3", pending);
        String out = run(operations, "request_bank_transfer",
                Map.of("from_account", "1000000005", "to_account", "1000000007", "amount", "2500", "note", "refund"), staff);
        assertTrue(out.contains("PENDING_CONFIRMATION"));
        pending.confirm(pending.forConversation("conv-3").getFirst().id(), "STAFF");
        assertEquals(new BigDecimal("8100.00"), balance("1000000007"));
    }

    @Test
    void moneyParsing() {
        assertEquals(new BigDecimal("1500.50"), Money.parse("₹ 1,500.5"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("0"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("1.005"));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("abc"));
    }

    @Test
    void fraudRulesPickWhichTransfersToReview() {
        // small, ordinary transfer -> no review
        Transaction small = bank.transfer("1000000005", "1000000001", new BigDecimal("500"), "T", "");
        assertTrue(fraud.triggers(small, bank.account("1000000005"), bank.transactionsOfAccount("1000000005", 25)).isEmpty());
        // large transfer -> review
        Transaction big = bank.transfer("1000000005", "1000000001", new BigDecimal("20000"), "T", "");
        assertFalse(fraud.triggers(big, bank.account("1000000005"), bank.transactionsOfAccount("1000000005", 25)).isEmpty());
        // draining an account -> review
        Transaction drain = bank.transfer("1000000007", "1000000001", new BigDecimal("5000"), "T", "");
        var t = fraud.triggers(drain, bank.account("1000000007"), bank.transactionsOfAccount("1000000007", 25));
        assertTrue(t.stream().anyMatch(s -> s.contains("80%")), t.toString());
    }
}
