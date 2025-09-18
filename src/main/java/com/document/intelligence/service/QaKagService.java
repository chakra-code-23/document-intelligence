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

        return graphService.queryRelevantEntities(question, documentId) // already Mono<String>
                .flatMap(graphContext -> {
                    log.info("Retrieved graph context length: {}", graphContext.length());

                    if (graphContext.isEmpty() || graphContext.trim().length() < 50) {
                        log.info("Insufficient graph context, falling back to RAG");
                        return qaService.chat(request); // already returns Mono<AnswerResponse>
                    }

                    String prompt = buildKagPrompt(question, graphContext);

                    return claudeApiService.callClaudeReactive(prompt)
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


    private String buildKagPrompt(String question, String graphContext) {
        return """
            You are a knowledgeable assistant with access to a knowledge graph containing entities and their relationships from documents.
            
            Use the following knowledge graph context to answer the question. The context contains entities mentioned in the documents and their relationships.
            
            KNOWLEDGE GRAPH CONTEXT:
            %s
            
            QUESTION: %s
            
            INSTRUCTIONS:
            - Use the knowledge graph information to provide a comprehensive answer
            - Reference specific entities and relationships when relevant
            - If the graph context doesn't fully answer the question, acknowledge what information is available
            - Be clear about the connections between entities
            - Provide a structured and informative response
            
            ANSWER:
            """.formatted(graphContext, question);
    }
}