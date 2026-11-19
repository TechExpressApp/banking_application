package com.bank.agents.core;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Something an agent proposed that changes money and needs a human to confirm it, e.g. a transfer.
 * The agent only creates it; it runs when the person calls POST /api/agents/actions/{id}/confirm.
 */
public record PendingAction(String id, String kind, String principal, String conversationId,
                            String summary, Map<String, String> details, Instant expiresAt,
                            Supplier<Object> executor) {
}
