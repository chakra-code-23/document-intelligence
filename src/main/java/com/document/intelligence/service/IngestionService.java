package com.document.intelligence.service;

import com.document.intelligence.dto.SplitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final PineconeVectorService pineconeVectorService;
    private final Neo4jGraphService neo4jGraphService;

    public void ingest(SplitResponse splitResponse, String documentName, String topicId) {
        if (splitResponse == null || splitResponse.getPages() == null) {
            log.warn("No parsed pages available for ingestion for documentId=null");
            return;
        }

        String documentId = splitResponse.getDocumentId();

        splitResponse.getPages().forEach(page -> {
            List<String> chunks = page.getChunks();
            String pageNo = String.valueOf(page.getPageNo());

            if (chunks == null || chunks.isEmpty()) {
                log.warn("Skipping empty page={} for documentId={}", pageNo, documentId);
                return;
            }

            Map<String, String> metadata = new java.util.HashMap<>();
            metadata.put("documentId", documentId);
            metadata.put("documentName", documentName);
            metadata.put("topicId", topicId);
            metadata.put("pageNo", pageNo);

            if (documentId == null || topicId == null) {
                throw new IllegalArgumentException("❌ Missing required metadata: documentId=" + documentId + ", topicId=" + topicId);
            }
            log.info("Ingesting metadata = {}", metadata);

            log.info("Ingesting {} chunks for documentId={} topicId={} pageNo={}",
                    chunks.size(), documentId, topicId, pageNo);

            try {
                // ✅ Store in Pinecone (RAG)
                pineconeVectorService.saveChunksToPineCone(chunks, metadata);
                log.info("✅ Pinecone ingestion completed for documentId={} pageNo={}", documentId, pageNo);

                // ✅ Store in Neo4j (KAG)
                neo4jGraphService.saveChunksToNeo4j(chunks, metadata);
                log.info("✅ Neo4j ingestion completed for documentId={} pageNo={}", documentId, pageNo);

            } catch (Exception e) {
                log.error("❌ Ingestion failed for documentId={} pageNo={} : {}", documentId, pageNo, e.getMessage(), e);
            }
        });
    }
}
