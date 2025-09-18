package com.document.intelligence.service;

import com.document.intelligence.dto.AnswerResponse;
import com.document.intelligence.dto.QuestionRequest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QaService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;
    private final LangChainOllamaService llmService;
    private final ClaudeApiService claudeApiService;


    public Mono<AnswerResponse> chat(QuestionRequest request) {
        log.info("Processing QA for documentId={}, topicId={}, question={}",
                request.getDocumentId(), request.getTopicId(), request.getQuestion());

        return Mono.fromCallable(() -> {
                    // 1) Embed the question (still blocking)
                    Embedding questionEmbedding = embeddingModel.embed(request.getQuestion()).content();

                    // 2) Build metadata filters
                    Filter fDoc = new MetadataFilterBuilder("documentId").isEqualTo(request.getDocumentId());
                    Filter fTopic = new MetadataFilterBuilder("topicId").isEqualTo(request.getTopicId());
                    Filter combined = new And(fDoc, fTopic);

                    // 3) Create EmbeddingSearchRequest
                    EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                            .queryEmbedding(questionEmbedding)
                            .maxResults(3)
                            .minScore(0.5)
                            .filter(combined)
                            .build();

                    // 4) Run the search
                    EmbeddingSearchResult<TextSegment> result = embeddingStore.search(searchRequest);
                    return result.matches().stream()
                            .map(m -> m.embedded().text())
                            .collect(Collectors.toList());
                })
                .flatMap(context -> {
                    log.info("Retrieved {} context items for QA (doc={}, topic={})",
                            context.size(), request.getDocumentId(), request.getTopicId());

                    // 6) Handle no-context case
                    if (context.isEmpty()) {
                        log.warn("⚠️ No context found for QA (doc={}, topic={})",
                                request.getDocumentId(), request.getTopicId());
                        return Mono.just(new AnswerResponse("No context regarding this question.", context));
                    }

                    // 7) Call the LLM reactively
                    return generateAnswer(request.getQuestion(), context)
                            .map(answer -> {
                                log.info("QA complete for docId={}", request.getDocumentId());
                                return new AnswerResponse(answer, context);
                            });
                });
    }

    public Mono<String> generateAnswer(String question, List<String> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful assistant. Use the following context to answer the question.\n\n");

        for (int i = 0; i < context.size(); i++) {
            String cleanContext = sanitizeContext(context.get(i));
            prompt.append("Context ").append(i + 1).append(": ").append(cleanContext).append("\n");
        }

        prompt.append("\nQuestion: ").append(question).append("\n");
        prompt.append("Answer in clear and concise English.");

        // always returns a Mono<String>
        return sanitizeText(claudeApiService.callClaudeReactive(prompt.toString()));
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

    private String sanitizeContext(String input) {
        if (input == null) {
            return "";
        }
        String result = input;
        result = result.replace("\"", "\\\"");
        result = result.replaceAll("\\n+", " ");    // replace newlines
        result = result.replaceAll("\\s{2,}", " "); // collapse spaces
        return result.trim();
    }

}
