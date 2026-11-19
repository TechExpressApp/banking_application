package com.bank.agents.core;

import com.bank.service.BankException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class PendingActionStore {

    private static final Duration TTL = Duration.ofMinutes(5);
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();

    public PendingAction create(String kind, ToolContext ctx, String summary, Map<String, String> details,
                                Supplier<Object> executor) {
        sweep();
        PendingAction a = new PendingAction("act-" + UUID.randomUUID().toString().substring(0, 13), kind,
                ctx.principal(), ctx.conversationId(), summary, details, Instant.now().plus(TTL), executor);
        actions.put(a.id(), a);
        return a;
    }

    public List<PendingAction> forConversation(String conversationId) {
        sweep();
        return actions.values().stream().filter(a -> a.conversationId().equals(conversationId)).toList();
    }

    /** Returns the conversation an action belongs to, after checking ownership (does not consume it). */
    public String forPrincipalAction(String id, String principal) {
        sweep();
        PendingAction a = actions.get(id);
        if (a == null) throw BankException.notFound("This action has expired or was already handled");
        if (!a.principal().equals(principal)) throw BankException.forbidden("This action belongs to someone else");
        return a.conversationId();
    }

    /** Removes the action and runs it. Each action can run at most once. */
    public Object confirm(String id, String principal) {
        PendingAction a = take(id, principal);
        return a.executor().get();
    }

    public PendingAction cancel(String id, String principal) {
        return take(id, principal);
    }

    private PendingAction take(String id, String principal) {
        sweep();
        PendingAction a = actions.get(id);
        if (a == null) throw BankException.notFound("This action has expired or was already handled");
        if (!a.principal().equals(principal)) throw BankException.forbidden("This action belongs to someone else");
        if (!actions.remove(id, a)) throw BankException.conflict("This action was already handled");
        return a;
    }

    private void sweep() {
        Instant now = Instant.now();
        actions.values().removeIf(a -> a.expiresAt().isBefore(now));
    }
}
