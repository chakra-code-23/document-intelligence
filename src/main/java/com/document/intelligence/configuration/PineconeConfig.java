package com.document.intelligence.configuration;

import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PineconeConfig {

    @Value("${pinecone.api.key}")
    private String apiKey;

    @Value("${pinecone.index.name}")
    private String index;

    @Value("${pinecone.environment}")
    private String environment;

    @Bean
    public PineconeEmbeddingStore pineconeEmbeddingStore() {
        return PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .index(index)
                .environment(environment)   // use environment
                .nameSpace("default")
                .metadataTextKey("text_segment")
                .build();
    }
}
