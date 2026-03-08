package com.document.intelligence.service;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
public class ClaudeApiService {

    private final AnthropicChatModel chatModel;

    public ClaudeApiService(@Value("${claude.api.key}") String apiKey) {

        this.chatModel = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName("claude-3-haiku-20240307")
                .maxTokens(1000)
                .build();
    }

    public Mono<String> callClaudeReactive(String prompt) {

        return Mono.fromCallable(() -> {
                    log.debug("Calling Claude with prompt length: {}", prompt.length());
                    return chatModel.generate(prompt);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.error("Claude API call failed: {}", e.getMessage(), e));
    }

    // Optional blocking version
    public String callClaude(String prompt) {
        return callClaudeReactive(prompt).block();
    }
}