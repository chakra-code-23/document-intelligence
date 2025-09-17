package com.document.intelligence.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public void saveChunksToPineCone(List<String> chunks, Map<String, String> metadata) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("No chunks provided for Pinecone storage");
            return;
        }

        // 1. Wrap each chunk into a TextSegment with metadata
        List<TextSegment> segments = IntStream.range(0, chunks.size())
                .mapToObj(i -> {
                    Metadata md = new Metadata();
                    metadata.forEach(md::add);
                    md.add("chunkIndex", String.valueOf(i));

                    return TextSegment.from(chunks.get(i), md);
                })
                .toList();

        // 2. Generate embeddings for all segments
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // 3. Store each embedding + segment into Pinecone
        IntStream.range(0, segments.size()).forEach(i -> {
            Embedding embedding = embeddings.get(i);
            TextSegment segment = segments.get(i);

            String id = UUID.randomUUID().toString();
            embeddingStore.add(embedding, segment);

            String pageNo = segment.metadata().get("pageNo");
            String chunkIndex = segment.metadata().get("chunkIndex");

            log.info("Stored chunk (pageNo={}, chunkIndex={}) in Pinecone with ID={} (docId={}, topicId={})",
                    pageNo, chunkIndex, id,
                    segment.metadata().get("documentId"),
                    segment.metadata().get("topicId"));
        });


        log.info("✅ Successfully stored {} chunks for docId={} into Pinecone",
                chunks.size(), metadata.get("documentId"));
    }


}
