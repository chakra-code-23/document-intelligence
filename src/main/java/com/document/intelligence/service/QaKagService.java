package com.document.intelligence.service;


import com.document.intelligence.dto.AnswerResponse;
import com.document.intelligence.dto.QuestionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QaKagService {

    private final LangChainOllamaService llmService;
    private final Neo4jGraphService graphService;
    private final QaService qaService;
    private final ClaudeApiService claudeApiService;

    public Mono<AnswerResponse> answer(QuestionRequest request) {
        log.info("Processing KAG for question={}", request.getQuestion());

        String question = request.getQuestion();
        String documentId = request.getDocumentId();

        return sanitizeText(graphService.queryRelevantEntities(question, documentId))// already Mono<String>
                .flatMap(graphContext -> {
                    log.info("Retrieved graph context length: {}", graphContext.length());

                    if (graphContext.isEmpty() || graphContext.trim().length() < 50) {
                        log.info("Insufficient graph context, falling back to RAG");
                        return qaService.chat(request); // already returns Mono<AnswerResponse>
                    }

                    String prompt = buildKagPrompt(question, graphContext);

                    return sanitizeText(claudeApiService.callClaudeReactive(prompt))
                            .map(kagAnswer -> {
                                log.info("✅ KAG answer generated successfully");
                                return new AnswerResponse(kagAnswer, List.of(graphContext));
                            });
                })
                .onErrorResume(e -> {
                    log.error("❌ KAG processing failed, falling back to RAG: {}", e.getMessage(), e);
                    return qaService.chat(request);
                });
    }

    private Mono<String> sanitizeText(Mono<String> inputMono) {
        return inputMono
                .map(input -> {
                    if (input == null) {
                        return "";
                    }
                    String result = input;
                    result = result.replace("\"", "\\\"");
                    result = result.replaceAll("\\n+", " ");    // replace newlines
                    result = result.replaceAll("\\s{2,}", " "); // collapse spaces
                    return result.trim();
                })
                .onErrorResume(e -> {
                    // fallback in case of exception
                    return Mono.just("");
                });
    }


    private String buildKagPrompt(String question, String graphContext) {
        return """
        You are a knowledgeable assistant with access to a knowledge graph of entities and their relationships.

        KNOWLEDGE GRAPH CONTEXT:
        %s

        QUESTION: %s

        INSTRUCTIONS:
        - Use the context as evidence for your answer
        - If entities are detected but context is missing, acknowledge it briefly and still attempt a concise answer
        - If relationships are shown, highlight them as the main evidence
        - Do NOT repeat phrases like "no context found" verbatim; instead, summarize clearly what is missing
        - Keep your answer direct and avoid speculation beyond the graph

        ANSWER:
        """.formatted(graphContext, question);
    }

}