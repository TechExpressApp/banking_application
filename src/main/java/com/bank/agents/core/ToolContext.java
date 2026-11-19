package com.bank.agents.core;

/**
 * Who the agent is acting for. Tools take identity from here, never from model input,
 * so a model cannot be talked into reading or moving another customer's money.
 *
 * @param principal   "CUST:&lt;customerId&gt;", "STAFF" or "PUBLIC"
 * @param customerId  set only for customer conversations
 */
public record ToolContext(String principal, String customerId, String conversationId, PendingActionStore pendingActions) {

    public String requireCustomer() {
        if (customerId == null) throw new IllegalStateException("This tool is only available to logged-in customers");
        return customerId;
    }
}
