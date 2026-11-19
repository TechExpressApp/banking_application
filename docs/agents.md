# AI agents

Four Claude-powered agents run inside the Java backend (Anthropic Java SDK, Messages API with tool use).

| Agent | Who uses it | UI page | Can do |
|---|---|---|---|
| **Banking Assistant** (`assistant`) | Logged-in customer | AI Assistant | Read own accounts, transactions, beneficiaries; add beneficiaries; *propose* transfers to beneficiaries |
| **Support / FAQ** (`support`) | Anyone | Help | Answer questions from `agents/support/knowledge-base.md`. No tools, no account data |
| **Operations** (`operations`) | Bank staff | Ops Assistant | Search accounts, reports, fraud-alert queue, *propose* transfers between any accounts |
| **Fraud Monitor** (`fraud`) | Runs automatically | Fraud Alerts | Reviews flagged transfers after they happen and raises MEDIUM/HIGH alerts |

## Setup

```bash
export ANTHROPIC_API_KEY=sk-ant-...      # from https://console.anthropic.com
mvn spring-boot:run
```
Without a key the bank works normally, and the chat pages show "AI agents are not set up yet".

## Safety design

- **Agents never move money directly.** `request_transfer` / `request_bank_transfer` create a *pending action* (valid for 5 minutes).
  The money only moves when the person presses **Confirm**, through `BankService` with all normal checks. Each action can run only once, and only its owner can confirm it.
- **Identity comes from the login session, never from the model.** Customer tools read `ToolContext.customerId`, so a prompt can't make the assistant read or spend another customer's money.
- **The fraud monitor only flags.** It never blocks or reverses a transfer. Simple rules choose what gets a model review
  (amount ≥ threshold, ≥ N transfers in 10 min, or moving ≥ 80% of the balance), so most transfers cost nothing.
- Strict tool schemas, a tool-round limit, and the server-side refusal fallback (`fallbacks: "default"`, beta) are on.

## Folder structure

```
src/main/java/com/bank/agents/
  core/         AgentRuntime (tool loop), AgentTool/FunctionTool/ToolSchema, AgentDefinition, ToolContext,
                Conversation(Store), PendingAction(Store), AgentProperties, AgentConfig (Anthropic client), PromptLoader, Money
  assistant/    BankingAssistantAgent  (tools defined inline)
  support/      SupportAgent
  operations/   OperationsAgent
  fraud/        FraudMonitorAgent, FraudAssessment (structured output), FraudAlert, FraudAlertStore
  web/          AgentController  (/api/agents/...)
src/main/resources/agents/
  assistant/system-prompt.md
  support/system-prompt.md, knowledge-base.md
  operations/system-prompt.md
  fraud/system-prompt.md
src/test/java/com/bank/agents/AgentToolsTest.java
```

To add an agent: create a package with a `@Component` that builds an `AgentDefinition` (prompt + `FunctionTool`s),
add its prompt under `resources/agents/<name>/`, and register it in `AgentController`.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/agents` | status |
| POST | `/api/agents/{assistant\|support\|operations}/chat` | `{conversationId?, message}` → `{conversationId, reply, toolCalls, pendingActions}`. `assistant` needs `Authorization: Bearer <token>` |
| POST | `/api/agents/{agent}/actions/{id}/confirm` · `/cancel` | runs or discards a proposed transfer |
| GET | `/api/agents/fraud/alerts?status=OPEN` | fraud queue |
| POST | `/api/agents/fraud/alerts/{id}/resolve` | `{note}` |

## Configuration (`application.properties`)

| Property | Default | |
|---|---|---|
| `banking.agents.enabled` | `true` | turn all agents off |
| `banking.agents.model` | `claude-opus-5-5` | per agent: `banking.agents.overrides.<agent>.model` |
| `banking.agents.effort` | `medium` | `low`…`max`; support uses `low` |
| `banking.agents.fallbacks` | `true` | server-side refusal fallback (beta) |
| `banking.agents.fraud.review-threshold` | `10000` | always review transfers at/above this |
| `banking.agents.fraud.velocity-count` | `3` | review when this many outgoing transfers in 10 min |
| `banking.agents.fraud.review-all` | `false` | review every transfer (one model call each) |

## Limitations

- Conversations and pending actions are kept in memory (lost on restart). Fraud alerts are saved in the `FraudAlerts` sheet/CSV.
- The Ops Assistant and Fraud Alerts pages have no staff login yet, same as the Bank Transfer page. Add staff authentication before real use.
