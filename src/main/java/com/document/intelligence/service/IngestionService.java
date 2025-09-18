package com.document.intelligence.service;

import com.document.intelligence.dto.SplitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final PineconeVectorService pineconeVectorService;
    private final Neo4jGraphService neo4jGraphService;

    public Mono<Void> ingest(SplitResponse splitResponse, String documentName, String topicId) {
        if (splitResponse == null || splitResponse.getPages() == null) {
            log.warn("No parsed pages available for ingestion for documentId=null");
            return Mono.empty();
        }

        String documentId = splitResponse.getDocumentId();

        return Flux.fromIterable(splitResponse.getPages())
                .flatMap(page -> {
                    List<String> chunks = page.getChunks();
                    String pageNo = String.valueOf(page.getPageNo());

                    if (chunks == null || chunks.isEmpty()) {
                        log.warn("Skipping empty page={} for documentId={}", pageNo, documentId);
                        return Mono.empty();
                    }

                    Map<String, String> metadata = new HashMap<>();
                    metadata.put("documentId", documentId);
                    metadata.put("documentName", documentName);
                    metadata.put("topicId", topicId);
                    metadata.put("pageNo", pageNo);

                    if (documentId == null || topicId == null) {
                        return Mono.error(new IllegalArgumentException("❌ Missing required metadata"));
                    }

                    log.info("🚀 Ingesting {} chunks for docId={} topicId={} pageNo={}",
                            chunks.size(), documentId, topicId, pageNo);

                    Mono<Void> pineconeIngest = pineconeVectorService.saveChunksToPineCone(chunks, metadata)
                            .doOnSuccess(v -> log.info("✅ Pinecone ingestion complete for page={} docId={}", pageNo, documentId));

                    Mono<Void> neo4jIngest = neo4jGraphService.saveChunksToNeo4j(chunks, metadata)
                            .doOnSuccess(v -> log.info("✅ Neo4j ingestion complete for page={} docId={}", pageNo, documentId));

                    return Mono.when(pineconeIngest, neo4jIngest)
                            .doOnSuccess(v -> log.info("🎯 Both Pinecone + Neo4j done for page={} docId={}", pageNo, documentId));
                })
                .then()
                .doOnSuccess(v -> log.info("🏁 Finished ingestion for documentId={} into BOTH Pinecone + Neo4j", documentId))
                .doOnError(e -> log.error("❌ Ingestion failed for documentId={}: {}", documentId, e.getMessage(), e));
    }


}
