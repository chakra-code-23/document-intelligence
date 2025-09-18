package com.document.intelligence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClaudeApiService {

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.url}")
    private String apiUrl;

    private final WebClient webClient;

    public Mono<String> callClaudeReactive(String prompt) {
        try {
            log.debug("Calling Claude API with prompt length: {}", prompt.length());

            return webClient.post()
                    .uri("v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")   // ✅ added
                    .bodyValue(buildRequest(prompt))
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).flatMap(errorBody -> {
                                log.error("Claude API returned error: {}", errorBody);
                                return Mono.error(new RuntimeException("Claude API call failed: " + errorBody));
                            })
                    )
                    .bodyToMono(String.class)
                    .map(this::extractTextFromResponse)
                    .doOnError(e -> log.error("Claude API call failed: {}", e.getMessage(), e));

        } catch (Exception e) {
            log.error("Claude API call failed before sending: {}", e.getMessage(), e);
            return Mono.error(new RuntimeException("Claude API call failed: " + e.getMessage(), e));
        }
    }

    // Keep the blocking version for non-reactive contexts
    public String callClaude(String prompt) {
        return callClaudeReactive(prompt).block();
    }

    private Map<String, Object> buildRequest(String prompt) {
        return Map.of(
                "model", "claude-sonnet-4-20250514",
                "max_tokens", 1000,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                )
        );
    }

    private String extractTextFromResponse(String jsonResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(jsonResponse);

            JsonNode content = root.path("content");
            if (content.isArray() && content.size() > 0) {
                return content.get(0).path("text").asText();
            }

            return "";
        } catch (Exception e) {
            log.error("Failed to parse Claude response: {}", e.getMessage());
            return "";
        }
    }
}