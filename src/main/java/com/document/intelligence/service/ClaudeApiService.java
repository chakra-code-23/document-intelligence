package com.document.intelligence.service;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ClaudeApiService {

    private final AnthropicChatModel chatModel;

    public ClaudeApiService(
            @Value("${claude.api.key}") String apiKey
    ) {

        this.chatModel = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-3-sonnet-20240229")
                .maxTokens(1000)
                .build();
    }

    public String callClaude(String prompt) {
        try {
            log.debug("Calling Claude with prompt length: {}", prompt.length());

            String response = chatModel.generate(prompt);

            log.debug("Claude response received");
            return response;

        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Claude API call failed", e);
        }
    }
}