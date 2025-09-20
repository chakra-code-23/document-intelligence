package com.document.intelligence.service;

import com.document.intelligence.dto.SplitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
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

        // Flatten all chunks across pages
        List<ChunkWithMetadata> allChunks = new ArrayList<>();
        for (var page : splitResponse.getPages()) {
            List<String> chunks = page.getChunks();
            String pageNo = String.valueOf(page.getPageNo());

            if (chunks == null || chunks.isEmpty()) {
                log.warn("Skipping empty page={} for documentId={}", pageNo, documentId);
                continue;
            }

            if (documentId == null || topicId == null) {
                return Mono.error(new IllegalArgumentException("❌ Missing required metadata"));
            }

            for (int i = 0; i < chunks.size(); i++) {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("documentId", documentId);
                metadata.put("documentName", documentName);
                metadata.put("topicId", topicId);
                metadata.put("pageNo", pageNo);
                metadata.put("chunkIndex", String.valueOf(i));

                allChunks.add(new ChunkWithMetadata(chunks.get(i), metadata));
            }
        }

        if (allChunks.isEmpty()) {
            log.warn("No chunks available to ingest for documentId={}", documentId);
            return Mono.empty();
        }

        log.info("🚀 Ingesting {} total chunks for docId={} topicId={}", allChunks.size(), documentId, topicId);

        // Step 1: Ingest ALL chunks into Neo4j (chunk by chunk)
        Mono<Void> neo4jIngestion = Flux.fromIterable(allChunks)
                .concatMap(chunk ->
                        neo4jGraphService.saveChunksToNeo4j(List.of(chunk.content), chunk.metadata)
                                .doOnSuccess(v -> log.info("✅ Neo4j ingestion complete for chunk={} page={} docId={}",
                                        chunk.metadata.get("chunkIndex"),
                                        chunk.metadata.get("pageNo"),
                                        documentId))
                )
                .then()
                .doOnSuccess(v -> log.info("✅ All chunks saved to Neo4j for docId={}", documentId));

        // Step 2: After Neo4j finishes → ingest ALL chunks into Pinecone
        Mono<Void> pineconeIngestion = Flux.fromIterable(allChunks)
                .concatMap(chunk ->
                        pineconeVectorService.saveChunksToPineCone(List.of(chunk.content), chunk.metadata)
                                .doOnSuccess(v -> log.info("✅ Pinecone ingestion complete for chunk={} page={} docId={}",
                                        chunk.metadata.get("chunkIndex"),
                                        chunk.metadata.get("pageNo"),
                                        documentId))
                )
                .then()
                .doOnSuccess(v -> log.info("✅ All chunks saved to Pinecone for docId={}", documentId));

        // Chain both stages and log success after both are done
        return neo4jIngestion
                .then(pineconeIngestion)
                .doOnSuccess(v -> log.info("🏁 FULL ingestion pipeline complete for docId={} → Neo4j ✅ → Pinecone ✅", documentId))
                .doOnError(e -> log.error("❌ Ingestion failed for documentId={}: {}", documentId, e.getMessage(), e));
    }

    record ChunkWithMetadata(String content, Map<String, String> metadata) {}

}
