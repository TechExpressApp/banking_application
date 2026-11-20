package com.bank.agents.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings under {@code banking.agents.*}. Each agent can override model/effort via
 * {@code banking.agents.overrides.<agent>.model} / {@code .effort}.
 */
@ConfigurationProperties(prefix = "banking.agents")
public class AgentProperties {

    private boolean enabled = true;
    private String model = "claude-opus-5-5";
    private String effort = "medium";
    private long maxTokens = 16000;
    private int maxToolRounds = 8;
    /** Send the server-side refusal fallback (beta) with every request. */
    private boolean fallbacks = true;
    /** Claude API key; normally set in config/application-local.properties (git-ignored), else ANTHROPIC_API_KEY is used. */
    private String apiKey;
    /** Workspace to use with an API key that isn't scoped to a workspace (sk-ant-usr-...). */
    private String workspaceId;
    private Map<String, Override> overrides = new HashMap<>();
    private Fraud fraud = new Fraud();

    public static class Override {
        private String model;
        private String effort;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getEffort() { return effort; }
        public void setEffort(String effort) { this.effort = effort; }
    }

    public static class Fraud {
        private boolean enabled = true;
        /** Transfers at or above this amount are always sent for review. */
        private String reviewThreshold = "10000";
        /** Review a transfer when the sender made this many transfers in the last 10 minutes. */
        private int velocityCount = 3;
        /** Review every transfer regardless of the rules above (costs one model call per transfer). */
        private boolean reviewAll = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getReviewThreshold() { return reviewThreshold; }
        public void setReviewThreshold(String reviewThreshold) { this.reviewThreshold = reviewThreshold; }
        public int getVelocityCount() { return velocityCount; }
        public void setVelocityCount(int velocityCount) { this.velocityCount = velocityCount; }
        public boolean isReviewAll() { return reviewAll; }
        public void setReviewAll(boolean reviewAll) { this.reviewAll = reviewAll; }
    }

    public String modelFor(String agent) {
        Override o = overrides.get(agent);
        return o != null && o.model != null ? o.model : model;
    }

    public String effortFor(String agent) {
        Override o = overrides.get(agent);
        return o != null && o.effort != null ? o.effort : effort;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }
    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public boolean isFallbacks() { return fallbacks; }
    public void setFallbacks(boolean fallbacks) { this.fallbacks = fallbacks; }
    public Map<String, Override> getOverrides() { return overrides; }
    public void setOverrides(Map<String, Override> overrides) { this.overrides = overrides; }
    public Fraud getFraud() { return fraud; }
    public void setFraud(Fraud fraud) { this.fraud = fraud; }
}
