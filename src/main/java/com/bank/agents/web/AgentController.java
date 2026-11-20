package com.bank.agents.web;

import com.bank.agents.assistant.BankingAssistantAgent;
import com.bank.agents.core.*;
import com.bank.agents.fraud.FraudAlert;
import com.bank.agents.fraud.FraudAlertStore;
import com.bank.agents.operations.OperationsAgent;
import com.bank.agents.support.SupportAgent;
import com.bank.model.Transaction;
import com.bank.service.BankException;
import com.bank.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Chat endpoints for the agents, plus confirm/cancel for actions they propose and the fraud-alert queue.
 *
 * <pre>
 * POST /api/agents/{agent}/chat                       {conversationId?, message}
 * POST /api/agents/{agent}/actions/{id}/confirm|cancel
 * GET  /api/agents/fraud/alerts?status=OPEN
 * POST /api/agents/fraud/alerts/{id}/resolve          {note}
 * </pre>
 * agent = assistant (needs customer login) | support (public) | operations (staff)
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    public record ChatRequest(String conversationId, @NotBlank @Size(max = 4000) String message) {}
    public record ResolveRequest(@Size(max = 500) String note) {}

    private final AgentRuntime runtime;
    private final ConversationStore conversations;
    private final PendingActionStore pendingActions;
    private final SessionService sessions;
    private final FraudAlertStore fraudAlerts;
    private final AgentProperties props;
    private final UsageBudget budget;
    private final Map<String, AgentDefinition> agents;

    public AgentController(AgentRuntime runtime, ConversationStore conversations, PendingActionStore pendingActions,
                           SessionService sessions, FraudAlertStore fraudAlerts, AgentProperties props, UsageBudget budget,
                           BankingAssistantAgent assistant, SupportAgent support, OperationsAgent operations) {
        this.runtime = runtime;
        this.conversations = conversations;
        this.pendingActions = pendingActions;
        this.sessions = sessions;
        this.fraudAlerts = fraudAlerts;
        this.props = props;
        this.budget = budget;
        this.agents = Map.of(
                BankingAssistantAgent.NAME, assistant.definition(),
                SupportAgent.NAME, support.definition(),
                OperationsAgent.NAME, operations.definition());
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("enabled", props.isEnabled(), "model", props.getModel(),
                "agents", List.of("assistant", "support", "operations", "fraud"),
                "fraudMonitor", props.getFraud().isEnabled(), "budget", budget.status());
    }

    @PostMapping("/{agent}/chat")
    public AgentReply chat(@PathVariable String agent, @Valid @RequestBody ChatRequest req,
                           @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        AgentDefinition def = agents.get(agent);
        if (def == null) throw BankException.notFound("No chat agent called '" + agent + "'");
        Principal p = principal(agent, auth);
        Conversation conv = conversations.getOrCreate(req.conversationId(), agent, p.id());
        ToolContext ctx = new ToolContext(p.id(), p.customerId(), conv.id(), pendingActions);
        return runtime.chat(def, conv, req.message(), ctx);
    }

    @PostMapping("/{agent}/actions/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String agent, @PathVariable String id,
                                       @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        Principal p = principal(agent, auth);
        String conversationId = pendingActions.forPrincipalAction(id, p.id());
        try {
            Object result = pendingActions.confirm(id, p.id());
            if (result instanceof Transaction t) {
                note(conversationId, "The user confirmed pending action " + id + ". Transfer completed: "
                        + t.amount() + " from " + t.fromAccount() + " to " + t.toAccount() + ", reference " + t.transactionId() + ".");
            }
            return Map.of("status", "CONFIRMED", "result", result);
        } catch (BankException e) {
            note(conversationId, "The user tried to confirm pending action " + id + " but it failed: " + e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{agent}/actions/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String agent, @PathVariable String id,
                                      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        Principal p = principal(agent, auth);
        PendingAction a = pendingActions.cancel(id, p.id());
        note(a.conversationId(), "The user cancelled pending action " + id + " (" + a.summary() + "). No money moved.");
        return Map.of("status", "CANCELLED");
    }

    @GetMapping("/fraud/alerts")
    public List<FraudAlert> alerts(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        return fraudAlerts.list(status, Math.max(1, Math.min(limit, 200)));
    }

    @PostMapping("/fraud/alerts/{id}/resolve")
    public FraudAlert resolve(@PathVariable String id, @Valid @RequestBody ResolveRequest req) {
        return fraudAlerts.resolve(id, req.note());
    }

    // ---------- helpers ----------

    private record Principal(String id, String customerId) {}

    private Principal principal(String agent, String auth) {
        return switch (agent) {
            case BankingAssistantAgent.NAME -> {
                String customerId = sessions.requireCustomer(auth);
                yield new Principal("CUST:" + customerId, customerId);
            }
            case SupportAgent.NAME -> new Principal("PUBLIC", null);
            // NOTE: like the Bank Transfer page, staff endpoints have no staff login yet.
            case OperationsAgent.NAME -> new Principal("STAFF", null);
            default -> throw BankException.notFound("No agent called '" + agent + "'");
        };
    }

    private void note(String conversationId, String text) {
        Conversation c = conversations.find(conversationId);
        if (c != null) c.addNote(text);
    }
}
