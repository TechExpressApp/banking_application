package com.bank.agents.core;

import com.anthropic.models.messages.Tool;

import java.util.Map;

/** A function an agent may call. Implementations live next to the agent that uses them. */
public interface AgentTool {

    String name();

    /** The tool definition sent to Claude (name, description, strict JSON schema). */
    Tool definition();

    /**
     * Runs the tool. Return plain text or JSON for the model to read.
     * Throw an exception to report an error back to the model (sent as an is_error tool result).
     */
    String execute(Map<String, Object> input, ToolContext ctx) throws Exception;
}
