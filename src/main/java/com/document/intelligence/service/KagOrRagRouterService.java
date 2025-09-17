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
    private final LangChainOllamaService llmService;    // used for classification

    public Mono<Object> route(QuestionRequest request) {
        return Mono.fromCallable(() -> {
            // Step 1: Ask LLM if question fits RAG or KAG
            String classificationPrompt = """
                    You are a strict question classifier.\s
                    Decide whether the question should be answered by:
                    
                    - KAG (Knowledge Graph): If the question involves entities, attributes, or relationships (e.g. "who is connected to X", "what is Y related to", "which skills belong to Z", "how does this network influence that").\s
                      Any question about "relationships", "networks", "connections", "links", or "influence within a group" MUST be KAG, even if the user also asks for explanations, evaluations, or references. \s
                    
                    - RAG (Retrieval-Augmented Generation): If the question asks for descriptive answers, summaries, narratives, or contextual analysis from documents that cannot be directly modeled as nodes/edges.
                    
                    Return strictly either "KAG" or "RAG". No justification, no explanation, no mixed answers.
                    
                    Question:
                    """ + request.getQuestion();

            String decisionRaw = llmService.askLangLlama(classificationPrompt);
            String decision = decisionRaw
                    .replaceAll("[^A-Za-z]", "")  // keep only letters
                    .trim()
                    .toUpperCase();

            log.info("Question classification result: {}", decision);

            // Step 2: Route based on decision
            if ("KAG".equalsIgnoreCase(decision)) {
                log.info("Routing question to KAG pipeline...");
                return kagService.answer(request);
            } else {
                log.info("Routing question to RAG pipeline...");
                // fallback = RAG
                return qaService.chat(request);
            }
        });
    }
}
