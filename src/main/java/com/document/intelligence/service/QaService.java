package com.document.intelligence.service;

import com.document.intelligence.dto.AnswerResponse;
import com.document.intelligence.dto.QuestionRequest;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
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


    public Mono<AnswerResponse> chat(QuestionRequest request) {
        log.info("Processing QA for documentId={}, topicId={}, question={}",
                request.getDocumentId(), request.getTopicId(), request.getQuestion());

        return Mono.fromCallable(() -> {

            // 1) Embed the question
            Embedding questionEmbedding = embeddingModel.embed(request.getQuestion()).content();

            // 2) Build metadata filters (documentId AND topicId)
            Filter fDoc = new MetadataFilterBuilder("documentId").isEqualTo(request.getDocumentId());
            Filter fTopic = new MetadataFilterBuilder("topicId").isEqualTo(request.getTopicId());

            log.info("QA filters: docId='{}', topicId='{}'", request.getDocumentId(), request.getTopicId());

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
            List<EmbeddingMatch<TextSegment>> matches = result.matches();

            // 5) Collect context
            List<String> context = matches.stream()
                    .map(m -> m.embedded().text())
                    .collect(Collectors.toList());

            log.info("Retrieved {} context items for QA (doc={}, topic={})",
                    context.size(), request.getDocumentId(), request.getTopicId());

            // 6) Handle no-context case
            if (context.isEmpty()) {
                log.warn("⚠️ No context found for QA (doc={}, topic={})",
                        request.getDocumentId(), request.getTopicId());
                return new AnswerResponse("No context regarding this question.", context);
            }

            // 7) Call the LLM
            String answer = llmService.generateAnswer(request.getQuestion(), context);

            log.info("QA complete for docId={}", request.getDocumentId());
            return new AnswerResponse(answer, context);
        });
    }

}
