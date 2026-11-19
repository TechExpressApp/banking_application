package com.bank.agents.core;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/** An {@link AgentTool} made from a definition plus a lambda. */
public record FunctionTool(Tool definition, Handler handler) implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @FunctionalInterface
    public interface Handler {
        Object handle(Map<String, Object> input, ToolContext ctx) throws Exception;
    }

    public static FunctionTool of(ToolSchema schema, Handler handler) {
        return new FunctionTool(schema.build(), handler);
    }

    @Override
    public String name() {
        return definition.name();
    }

    @Override
    public String execute(Map<String, Object> input, ToolContext ctx) throws Exception {
        Object out = handler.handle(input, ctx);
        return out instanceof String s ? s : JSON.writeValueAsString(out);
    }
}
