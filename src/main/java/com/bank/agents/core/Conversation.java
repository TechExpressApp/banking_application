package com.bank.agents.core;

import com.anthropic.models.messages.MessageParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One chat thread with one agent. The message history is append-only: assistant turns are stored exactly
 * as the API returned them, which keeps prompt caching and thinking blocks valid across turns.
 */
public class Conversation {

    private final String id;
    private final String agent;
    private final String principal;
    private final List<MessageParam> messages = new ArrayList<>();
    /** Out-of-band updates (e.g. "transfer confirmed") added to the start of the next user message. */
    private final List<String> pendingNotes = new ArrayList<>();
    private volatile Instant lastUsed = Instant.now();

    public Conversation(String id, String agent, String principal) {
        this.id = id;
        this.agent = agent;
        this.principal = principal;
    }

    public String id() { return id; }
    public String agent() { return agent; }
    public String principal() { return principal; }
    public List<MessageParam> messages() { return messages; }
    public Instant lastUsed() { return lastUsed; }
    public void touch() { lastUsed = Instant.now(); }

    public synchronized void addNote(String note) { pendingNotes.add(note); }

    public synchronized String takeNotes() {
        if (pendingNotes.isEmpty()) return "";
        String s = String.join("\n", pendingNotes);
        pendingNotes.clear();
        return s;
    }
}
