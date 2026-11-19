package com.bank.agents.core;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small builder for strict tool definitions: {@code ToolSchema.tool("name", "desc").string("x", "...", true).build()}. */
public final class ToolSchema {

    private final String name;
    private final String description;
    private final Map<String, Object> props = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private ToolSchema(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public static ToolSchema tool(String name, String description) {
        return new ToolSchema(name, description);
    }

    public ToolSchema string(String prop, String description, boolean isRequired) {
        return prop(prop, Map.of("type", "string", "description", description), isRequired);
    }

    public ToolSchema integer(String prop, String description, boolean isRequired) {
        return prop(prop, Map.of("type", "integer", "description", description), isRequired);
    }

    private ToolSchema prop(String prop, Map<String, Object> schema, boolean isRequired) {
        props.put(prop, schema);
        if (isRequired) required.add(prop);
        return this;
    }

    public Tool build() {
        Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(name)
                .description(description)
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(p.build())
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    // ---- input helpers ----

    public static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : v.toString().trim();
    }

    public static int intOr(Map<String, Object> in, String key, int dflt, int max) {
        Object v = in.get(key);
        if (v == null) return dflt;
        int n = v instanceof Number num ? num.intValue() : Integer.parseInt(v.toString());
        return Math.max(1, Math.min(n, max));
    }
}
