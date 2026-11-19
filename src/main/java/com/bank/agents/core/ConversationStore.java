package com.bank.agents.core;

import com.bank.service.BankException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory conversations, expired after 30 minutes idle. Lost on restart. */
@Component
public class ConversationStore {

    private static final Duration IDLE = Duration.ofMinutes(30);
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    /** Returns the existing conversation (checking it belongs to this principal and agent) or starts a new one. */
    public Conversation getOrCreate(String id, String agent, String principal) {
        sweep();
        if (id != null && !id.isBlank()) {
            Conversation c = conversations.get(id);
            if (c == null) throw BankException.notFound("Conversation expired or not found. Start a new one.");
            if (!c.principal().equals(principal) || !c.agent().equals(agent)) {
                throw BankException.forbidden("This conversation belongs to someone else");
            }
            c.touch();
            return c;
        }
        Conversation c = new Conversation("conv-" + UUID.randomUUID(), agent, principal);
        conversations.put(c.id(), c);
        return c;
    }

    public Conversation find(String id) {
        return id == null ? null : conversations.get(id);
    }

    private void sweep() {
        Instant cutoff = Instant.now().minus(IDLE);
        conversations.values().removeIf(c -> c.lastUsed().isBefore(cutoff));
    }
}
