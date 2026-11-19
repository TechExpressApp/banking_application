package com.bank.agents.support;

import com.bank.agents.core.AgentDefinition;
import com.bank.agents.core.PromptLoader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Answers questions about the bank's rules and how to use the app. Has no tools and no access to
 * any customer data; its knowledge comes from agents/support/knowledge-base.md.
 */
@Component
public class SupportAgent {

    public static final String NAME = "support";
    private final AgentDefinition definition = new AgentDefinition(NAME,
            PromptLoader.load("support/system-prompt.md") + "\n\n<knowledge_base>\n"
                    + PromptLoader.load("support/knowledge-base.md") + "\n</knowledge_base>",
            List.of());

    public AgentDefinition definition() {
        return definition;
    }
}
