package com.document.intelligence.service;


import com.document.intelligence.dto.AnswerResponse;
import com.document.intelligence.dto.QuestionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QaKagService {

    private final LangChainOllamaService llmService;
    private final Neo4jGraphService graphService;
    private final QaService qaService;

    public AnswerResponse answer(QuestionRequest request) {
        log.info("Processing KAG for question={}", request.getQuestion());

        String question = request.getQuestion();
        String documentId = request.getDocumentId(); // Assuming this exists in QuestionRequest

        try {
            // Step 1: Query Neo4j for entities/relationships
            String graphContext = graphService.queryRelevantEntities(question, documentId);
            log.info("Retrieved graph context length: {}", graphContext.length());

            // Step 2: If no graph context, fallback to RAG
            if (graphContext.isEmpty() || graphContext.trim().length() < 50) {
                log.info("Insufficient graph context, falling back to RAG");
                return qaService.chat(request).block(); // Fallback to your existing RAG service
            }

            // Step 3: Generate KAG-based answer using graph context
            String kagAnswer = generateKagAnswer(question, graphContext);

            log.info("✅ KAG answer generated successfully");
            return new AnswerResponse(kagAnswer, List.of(graphContext));

        } catch (Exception e) {
            log.error("❌ KAG processing failed, falling back to RAG: {}", e.getMessage(), e);
            return qaService.chat(request).block(); // Fallback to RAG on any error
        }
    }

    private String generateKagAnswer(String question, String graphContext) {
        String prompt = buildKagPrompt(question, graphContext);
        return llmService.askLangLlama(prompt);
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