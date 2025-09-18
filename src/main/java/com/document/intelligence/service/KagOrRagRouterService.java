package com.document.intelligence.service;


import com.document.intelligence.dto.QuestionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Routes question either to RAG (embedding-based) or KAG (graph-based) pipeline
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KagOrRagRouterService {

    private final QaService qaService;                  // your existing RAG pipeline
    private final QaKagService kagService;                // new KAG pipeline you will define
    private final LangChainOllamaService llmService;
    private final ClaudeApiService claudeApiService;

    public Mono<Object> route(QuestionRequest request) {
        String classificationPrompt = """
            You are a strict question classifier.
            Decide whether the question should be answered by:

            - KAG (Knowledge Graph): If the question involves entities, attributes, or relationships (e.g. "who is connected to X", "what is Y related to", "which skills belong to Z", "how does this network influence that").
              Any question about "relationships", "networks", "connections", "links", or "influence within a group" MUST be KAG, even if the user also asks for explanations, evaluations, or references. 

            - RAG (Retrieval-Augmented Generation): If the question asks for descriptive answers, summaries, narratives, or contextual analysis from documents that cannot be directly modeled as nodes/edges.

            Return strictly either "KAG" or "RAG". No justification, no explanation, no mixed answers.

            Question:
            """ + request.getQuestion();

        // ✅ Use reactive Claude call
        return claudeApiService.callClaudeReactive(classificationPrompt)
                .map(decisionRaw -> {
                    String decision = decisionRaw
                            .replaceAll("[^A-Za-z]", "")
                            .trim()
                            .toUpperCase();

                    log.info("Question classification result: {}", decision);
                    return decision;
                })
                .flatMap(decision -> {
                    if ("KAG".equalsIgnoreCase(decision)) {
                        log.info("Routing question to KAG pipeline...");
                        return kagService.answer(request).cast(Object.class);
                    } else {
                        log.info("Routing question to RAG pipeline...");
                        return qaService.chat(request).cast(Object.class);
                    }
                })
                .onErrorResume(e -> {
                    log.error("❌ Error in route(): {}", e.getMessage(), e);
                    return qaService.chat(request).cast(Object.class); // fallback to RAG
                });
    }

}
