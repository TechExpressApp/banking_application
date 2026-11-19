package com.bank.agents.core;

import java.util.List;
import java.util.Map;

/** What the API returns for one chat turn. */
public record AgentReply(String conversationId, String agent, String reply, List<Map<String, Object>> toolCalls,
                         List<Map<String, Object>> pendingActions) {
}
