package com.multi.vidulum.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that provides a mock ChatModel.
 * This replaces the real AI providers (OpenAI/Anthropic) in tests.
 *
 * application-test.yml excludes:
 * - OpenAiChatAutoConfiguration
 * - AnthropicChatAutoConfiguration
 * - AiProviderConfig
 *
 * So this mock becomes the only ChatModel available.
 */
@TestConfiguration
public class TestAiConfig {

    /**
     * Provides a mock ChatModel for tests.
     * Primary bean that replaces all real AI providers.
     */
    @Bean
    @Primary
    public ChatModel mockChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                // Return a mock response - tests that need real AI should use @DirtiesContext
                // and provide their own ChatModel
                return new ChatResponse(
                    java.util.List.of(
                        new Generation(
                            new AssistantMessage("Mock AI response - not for production use")
                        )
                    )
                );
            }
        };
    }
}
