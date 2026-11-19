package com.bank.agents.core;

import java.util.List;

/**
 * An agent = a name, a system prompt (from src/main/resources/agents/&lt;name&gt;/) and the tools it may call.
 */
public record AgentDefinition(String name, String systemPrompt, List<AgentTool> tools) {

    public AgentTool tool(String toolName) {
        return tools.stream().filter(t -> t.name().equals(toolName)).findFirst().orElse(null);
    }
}
