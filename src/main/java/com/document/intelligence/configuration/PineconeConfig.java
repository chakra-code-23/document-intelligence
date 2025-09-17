package com.document.intelligence.configuration;

import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconePodIndexConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class PineconeConfig {

    @Value("${pinecone.api.key}")
    private String apiKey;

    @Value("${pinecone.environment}")
    private String environment;

    @Value("${pinecone.index.name}")
    private String indexName;

    @Value("${pinecone.index.dimension}")
    private Integer dimension;

    @Value("${pinecone.index.podType}")
    private String podType;

    @Bean
    public PineconeEmbeddingStore pineconeEmbeddingStore() {
        return PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .nameSpace("default")
                .index(indexName)
                .build();
    }
}