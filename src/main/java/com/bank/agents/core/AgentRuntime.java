package com.bank.agents.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.*;
import com.bank.service.BankException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Runs one chat turn: sends the conversation to Claude, executes any tool calls it makes, sends the
 * results back, and repeats until Claude answers (or the tool-round limit is reached).
 */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ObjectProvider<AnthropicClient> client;
    private final AgentProperties props;
    private final PendingActionStore pendingActions;
    private final UsageBudget budget;

    public AgentRuntime(ObjectProvider<AnthropicClient> client, AgentProperties props, PendingActionStore pendingActions,
                        UsageBudget budget) {
        this.client = client;
        this.props = props;
        this.pendingActions = pendingActions;
        this.budget = budget;
    }

    public AgentReply chat(AgentDefinition agent, Conversation conv, String userText, ToolContext ctx) {
        if (!props.isEnabled()) throw new BankException(HttpStatus.SERVICE_UNAVAILABLE, "AI agents are turned off");
        if (userText == null || userText.isBlank()) throw BankException.badRequest("Message is empty");
        if (userText.length() > 4000) throw BankException.badRequest("Message is too long (max 4000 characters)");

        synchronized (conv) {                       // one turn at a time per conversation
            String notes = conv.takeNotes();
            String text = notes.isEmpty() ? userText : "[Update since your last reply]\n" + notes + "\n\n" + userText;
            conv.messages().add(MessageParam.ofUser(UserMessageParam.builder().content(text).build()));

            List<Map<String, Object>> toolCalls = new ArrayList<>();
            StringBuilder reply = new StringBuilder();

            for (int round = 0; round <= props.getMaxToolRounds(); round++) {
                Message response = call(agent, conv.messages(), round == props.getMaxToolRounds());
                conv.messages().add(response.toParam());          // append exactly as returned

                response.content().forEach(b -> b.text().ifPresent(t -> {
                    if (!reply.isEmpty()) reply.append("\n\n");
                    reply.append(t.text());
                }));

                StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
                if (stop.equals(StopReason.REFUSAL)) {
                    reply.setLength(0);
                    reply.append("Sorry, I can't help with that request.");
                    break;
                }
                if (!stop.equals(StopReason.TOOL_USE)) {
                    if (stop.equals(StopReason.MAX_TOKENS)) reply.append("\n\n(My answer was cut short.)");
                    break;
                }

                // run every tool call from this turn, return all results in ONE user message
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(use -> results.add(runTool(agent, use, ctx, toolCalls)));
                }
                conv.messages().add(MessageParam.ofUserOfContentBlockParams(results));
            }

            List<Map<String, Object>> pending = pendingActions.forConversation(conv.id()).stream()
                    .map(a -> Map.<String, Object>of("id", a.id(), "kind", a.kind(), "summary", a.summary(),
                            "details", a.details(), "expiresAt", a.expiresAt().toString()))
                    .toList();
            String out = reply.isEmpty() ? "(No reply. Please try rephrasing.)" : reply.toString();
            return new AgentReply(conv.id(), agent.name(), out, toolCalls, pending);
        }
    }

    private Message call(AgentDefinition agent, List<MessageParam> messages, boolean lastRound) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(props.modelFor(agent.name()))
                .maxTokens(props.getMaxTokens())
                // system prompt is stable -> cache it (tools render before system, so they are cached too)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(agent.systemPrompt())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effortFor(agent.name()))).build())
                .messages(messages);
        // Same tool list every round (keeps the cache valid). On the last allowed round,
        // tool_choice "none" makes Claude answer with what it already has.
        agent.tools().forEach(t -> b.addTool(t.definition()));
        if (lastRound && !agent.tools().isEmpty()) b.toolChoice(ToolChoiceNone.builder().build());
        if (props.isFallbacks()) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        budget.requireBudget();                     // refuse once today's AI budget is used up
        try {
            Message m = client.getObject().messages().create(b.build());
            budget.record(m.model().asString(), m.usage());
            return m;
        } catch (RateLimitException e) {
            throw new BankException(HttpStatus.TOO_MANY_REQUESTS, "The assistant is busy right now. Please try again in a moment.");
        } catch (AnthropicServiceException e) {
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                log.warn("Claude API rejected the credentials: {}", e.getMessage());
                throw new BankException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The AI agents are not set up yet: add your API key to config/application-local.properties and restart the server.");
            }
            log.warn("Claude API error {}: {}", e.statusCode(), e.getMessage());
            throw new BankException(HttpStatus.BAD_GATEWAY, "The assistant is unavailable (" + e.statusCode() + ")");
        } catch (AnthropicException | IllegalStateException e) {
            log.warn("Claude API not reachable: {}", e.getMessage());
            throw new BankException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The assistant is not configured. Set ANTHROPIC_API_KEY and restart the server.");
        }
    }

    private ContentBlockParam runTool(AgentDefinition agent, ToolUseBlock use, ToolContext ctx, List<Map<String, Object>> log) {
        AgentTool tool = agent.tool(use.name());
        String result;
        boolean error = false;
        try {
            if (tool == null) throw new IllegalArgumentException("Unknown tool " + use.name());
            Map<String, Object> input = use._input().convert(new TypeReference<Map<String, Object>>() {});
            result = tool.execute(input == null ? Map.of() : input, ctx);
        } catch (Exception e) {
            error = true;
            result = "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        log.add(Map.of("tool", use.name(), "error", error));
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(use.id())
                .content(result)
                .isError(error)
                .build());
    }
}
