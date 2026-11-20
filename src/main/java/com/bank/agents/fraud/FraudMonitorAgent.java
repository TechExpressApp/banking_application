package com.bank.agents.fraud;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.bank.agents.core.AgentProperties;
import com.bank.agents.core.PromptLoader;
import com.bank.agents.core.UsageBudget;
import com.bank.model.Account;
import com.bank.model.Transaction;
import com.bank.service.BankService;
import com.bank.service.TransferCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Reviews transfers after they happen. Cheap rules pick which transfers need a look; those get one Claude
 * call that returns a structured {@link FraudAssessment}. MEDIUM/HIGH results become fraud alerts for staff.
 * It never blocks or reverses a transfer itself.
 */
@Component
public class FraudMonitorAgent {

    public static final String NAME = "fraud";
    private static final Logger log = LoggerFactory.getLogger(FraudMonitorAgent.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<AnthropicClient> client;
    private final AgentProperties props;
    private final BankService bank;
    private final FraudAlertStore alerts;
    private final UsageBudget budget;
    private final String systemPrompt = PromptLoader.load("fraud/system-prompt.md");

    public FraudMonitorAgent(ObjectProvider<AnthropicClient> client, AgentProperties props, BankService bank,
                             FraudAlertStore alerts, UsageBudget budget) {
        this.client = client;
        this.props = props;
        this.bank = bank;
        this.alerts = alerts;
        this.budget = budget;
    }

    @Async
    @EventListener
    public void onTransfer(TransferCompletedEvent event) {
        if (!props.isEnabled() || !props.getFraud().isEnabled()) return;
        try {
            review(event.transaction());
        } catch (Exception e) {
            log.warn("Fraud review of {} failed: {}", event.transaction().transactionId(), e.getMessage());
        }
    }

    /** Returns the triggers that make a transfer worth reviewing; empty = skip the model call. */
    public List<String> triggers(Transaction t, Account sender, List<Transaction> senderHistory) {
        List<String> out = new ArrayList<>();
        AgentProperties.Fraud cfg = props.getFraud();
        if (cfg.isReviewAll()) out.add("review-all mode");
        if (t.amount().compareTo(new BigDecimal(cfg.getReviewThreshold())) >= 0) {
            out.add("amount at or above review threshold " + cfg.getReviewThreshold());
        }
        Instant since = Instant.parse(t.timestamp()).minus(Duration.ofMinutes(10));
        long recentOut = senderHistory.stream()
                .filter(x -> x.fromAccount().equals(t.fromAccount()))
                .filter(x -> !Instant.parse(x.timestamp()).isBefore(since))
                .count();
        if (recentOut >= cfg.getVelocityCount()) out.add(recentOut + " outgoing transfers within 10 minutes");
        BigDecimal before = sender.balance().add(t.amount());
        if (before.signum() > 0 && t.amount().multiply(BigDecimal.valueOf(100)).compareTo(before.multiply(BigDecimal.valueOf(80))) >= 0) {
            out.add("moved 80% or more of the account balance");
        }
        return out;
    }

    public Optional<FraudAlert> review(Transaction t) throws Exception {
        Account sender = bank.account(t.fromAccount());
        Account receiver = bank.account(t.toAccount());
        List<Transaction> history = bank.transactionsOfAccount(t.fromAccount(), 25);
        List<String> triggers = triggers(t, sender, history);
        if (triggers.isEmpty()) return Optional.empty();
        if (!budget.hasBudget()) {
            log.warn("Fraud review of {} skipped: today's AI budget is used up (triggers: {})", t.transactionId(), triggers);
            return Optional.empty();
        }

        Map<String, Object> caseFile = new LinkedHashMap<>();
        caseFile.put("transaction", t);
        caseFile.put("triggers", triggers);
        caseFile.put("sender", Map.of("accountNumber", sender.accountNumber(), "holder", sender.holderName(),
                "type", sender.accountType(), "balanceAfter", sender.balance(), "accountOpened", sender.createdAt()));
        caseFile.put("receiver", Map.of("accountNumber", receiver.accountNumber(), "holder", receiver.holderName(),
                "type", receiver.accountType(), "accountOpened", receiver.createdAt()));
        caseFile.put("senderRecentTransactions", history);

        StructuredMessageCreateParams.Builder<FraudAssessment> b = MessageCreateParams.builder()
                .model(props.modelFor(NAME))
                .maxTokens(4000L)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessage("Assess this transfer:\n" + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(caseFile))
                .outputConfig(FraudAssessment.class);
        b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(props.effortFor(NAME))).build());
        if (props.isFallbacks()) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        StructuredMessage<FraudAssessment> msg = client.getObject().messages().create(b.build());
        budget.record(msg.model().asString(), msg.usage());
        if (msg.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            log.warn("Fraud review of {} was declined by the model", t.transactionId());
            return Optional.empty();
        }
        FraudAssessment a = msg.content().stream().flatMap(c -> c.text().stream()).map(StructuredTextBlock::text)
                .findFirst().orElseThrow(() -> new IllegalStateException("No assessment returned"));
        log.info("Fraud review {}: {} ({}) {}", t.transactionId(), a.riskLevel(), a.riskScore(), a.reasons());

        String level = a.riskLevel() == null ? "LOW" : a.riskLevel().toUpperCase();
        if (level.equals("LOW")) return Optional.empty();
        FraudAlert alert = new FraudAlert("FA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                Instant.now().toString(), t.transactionId(), t.fromAccount(), t.toAccount(), t.amount().toPlainString(),
                level, a.riskScore(), String.join(" | ", a.reasons() == null ? List.of() : a.reasons()),
                a.recommendedAction(), String.join(" | ", triggers), "OPEN", "");
        alerts.add(alert);
        return Optional.of(alert);
    }
}
