package com.document.intelligence.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
@Slf4j
public class PineconeVectorService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public PineconeVectorService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public String saveText(String text) {
        // 1. generate embedding
        Embedding embedding = embeddingModel.embed(text).content();

        log.info("Generated embedding vector (size={}): {}",
                embedding.vectorAsList().size(),
                embedding.vectorAsList());

        // 2. wrap into a TextSegment (so it saves metadata too)
        TextSegment segment = TextSegment.from(text);

        // 3. store in Pinecone with UUID
        String id = UUID.randomUUID().toString();
        embeddingStore.add(embedding, TextSegment.from(text));

        return "Stored in Pinecone with ID: " + id;
    }

    /**
     * Save multiple chunks with metadata into Pinecone
     *
     * @param chunks     list of text chunks
     * @param metadata   common metadata (e.g. documentId, topicId, pageNo, etc.)
     */
    /**
     * Save multiple chunks with metadata into Pinecone
     *
     * @param chunks   list of text chunks
     * @param metadata map of metadata (docId, topicId, pageNo, etc.)
     */

    public Mono<Void> saveChunksToPineCone(List<String> chunks, Map<String, String> metadata) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("⚠️ No chunks provided for Pinecone storage");
            return Mono.empty();
        }

        String docId = metadata.get("documentId");
        String pageNo = metadata.get("pageNo");

        log.info("📥 Saving {} chunks to Pinecone for docId={} page={}", chunks.size(), docId, pageNo);

        return Flux.fromIterable(IntStream.range(0, chunks.size()).boxed().toList())
                .flatMap(i -> {
                    Metadata md = new Metadata();
                    metadata.forEach(md::add);
                    md.add("chunkIndex", String.valueOf(i));

                    TextSegment segment = TextSegment.from(chunks.get(i), md);

                    // Step 1: Embed the chunk (blocking -> run on elastic)
                    return Mono.fromCallable(() -> embeddingModel.embed(segment).content())
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(embedding -> Map.of("embedding", embedding, "segment", segment));
                })

                .flatMap(entry -> {
                    Embedding embedding = (Embedding) entry.get("embedding");
                    TextSegment segment = (TextSegment) entry.get("segment");

                    // Step 2: Store in Pinecone (blocking -> run on elastic)
                    return Mono.fromCallable(() -> {
                        String id = UUID.randomUUID().toString();
                        embeddingStore.add(embedding, segment);

                        log.debug("Pinecone: stored chunk docId={} pageNo={} chunkIndex={} id={}",
                                segment.metadata().get("documentId"),
                                segment.metadata().get("pageNo"),
                                segment.metadata().get("chunkIndex"),
                                id);

                        return id;
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .then()
                .doOnSuccess(v -> log.info("✅ Pinecone finished saving {} chunks for docId={} page={}", chunks.size(), docId, pageNo))
                .doOnError(e -> log.error("❌ Pinecone ingestion failed for docId={} page={}: {}", docId, pageNo, e.getMessage(), e));
    }

}
