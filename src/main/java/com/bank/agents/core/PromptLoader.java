package com.bank.agents.core;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads prompt files from {@code src/main/resources/agents/}. */
public final class PromptLoader {
    private PromptLoader() {}

    public static String load(String path) {
        try (var in = new ClassPathResource("agents/" + path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing prompt file agents/" + path, e);
        }
    }
}
