package com.multi.vidulum.bank_data_adapter.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

/**
 * Configuration for AI provider selection.
 * Selects ChatModel based on OPENAI_ENABLED / ANTHROPIC_ENABLED flags.
 *
 * Usage - set environment variables:
 *   Anthropic (default):
 *     ANTHROPIC_API_KEY=sk-ant-...
 *     ANTHROPIC_ENABLED=true (default)
 *     OPENAI_ENABLED=false (default)
 *
 *   OpenAI:
 *     OPENAI_API_KEY=sk-proj-...
 *     ANTHROPIC_ENABLED=false
 *     OPENAI_ENABLED=true
 */
@Slf4j
@Configuration
public class AiProviderConfig {

    @Value("${spring.ai.openai.chat.enabled:false}")
    private boolean openaiEnabled;

    @Value("${spring.ai.anthropic.chat.enabled:true}")
    private boolean anthropicEnabled;

    @Bean
    @Primary
    public ChatModel primaryChatModel(Map<String, ChatModel> chatModels) {
        log.info("Available AI providers: {}", chatModels.keySet());
        log.info("OpenAI enabled: {}, Anthropic enabled: {}", openaiEnabled, anthropicEnabled);

        if (chatModels.isEmpty()) {
            throw new IllegalStateException(
                "No ChatModel beans found. Enable at least one AI provider."
            );
        }

        // Select based on enabled flags
        String selectedBean;
        if (openaiEnabled && chatModels.containsKey("openAiChatModel")) {
            selectedBean = "openAiChatModel";
        } else if (anthropicEnabled && chatModels.containsKey("anthropicChatModel")) {
            selectedBean = "anthropicChatModel";
        } else {
            // Fallback to first available
            selectedBean = chatModels.keySet().iterator().next();
            log.warn("No explicitly enabled provider found, using fallback: {}", selectedBean);
        }

        ChatModel selected = chatModels.get(selectedBean);
        log.info("Using ChatModel: {} ({})", selectedBean, selected.getClass().getSimpleName());
        return selected;
    }
}
