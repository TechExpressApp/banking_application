package com.bank.agents.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

    /**
     * Reads credentials the standard way: ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, or an `ant auth login` profile.
     * Lazy, so the bank still starts when no credentials are configured; agent calls then fail with a clear error.
     */
    @Bean
    @Lazy
    public AnthropicClient anthropicClient(AgentProperties props) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().fromEnv();
        // A key in config/application-local.properties (git-ignored) wins over ANTHROPIC_API_KEY.
        String key = props.getApiKey();
        if (key != null && !key.isBlank()) b.apiKey(key.trim());
        // Needed only for keys that are not scoped to one workspace (they start with sk-ant-usr-).
        String ws = props.getWorkspaceId();
        if (ws != null && !ws.isBlank()) b.putHeader("anthropic-workspace-id", ws.trim());
        return b.build();
    }
}
