package com.document.intelligence.service;


import com.document.intelligence.dto.EntityInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class Neo4jGraphService {

    private final Neo4jClient neo4jClient;
    private final EntityExtractionService entityExtractionService;

    /**
     * Save chunks + entities into Neo4j
     */
    public Mono<Void> saveChunksToNeo4j(List<String> chunks, Map<String, String> metadata) {
        String documentId = metadata.get("documentId");
        String documentName = metadata.get("documentName");
        String topicId = metadata.get("topicId");
        String pageNo = metadata.get("pageNo");

        log.info("Saving {} chunks to Neo4j for document={} page={}", chunks.size(), documentId, pageNo);

        return Mono.fromRunnable(() -> createDocumentNode(documentId, documentName, topicId))
                .thenMany(Flux.fromIterable(chunks)
                        .index()
                        .flatMap(tuple -> {
                            long i = tuple.getT1();
                            String chunk = tuple.getT2();
                            String chunkId = documentId + "_page" + pageNo + "_chunk" + i;

                            return entityExtractionService.extractEntities(chunk)
                                    .map(entities -> entities.stream()
                                            .filter(e -> e != null &&
                                                    e.getName() != null && !e.getName().trim().isEmpty() &&
                                                    e.getType() != null && !e.getType().trim().isEmpty())
                                            .collect(Collectors.toList()))
                                    .doOnNext(entities -> {
                                        createChunkNode(chunkId, chunk, documentId, pageNo, (int) i);

                                        for (EntityInfo entity : entities) {
                                            try {
                                                createEntityNode(entity);
                                                createChunkEntityRelationship(chunkId, entity.getName(), entity.getType());
                                            } catch (Exception e) {
                                                log.error("Failed to process entity '{}' of type '{}': {}",
                                                        entity.getName(), entity.getType(), e.getMessage());
                                            }
                                        }

                                        if (entities.size() > 1) {
                                            createEntityRelationships(entities, chunkId);
                                        }
                                    });
                        }))
                .then()
                .doOnSuccess(v -> log.info("✅ Successfully saved {} chunks to Neo4j", chunks.size()))
                .doOnError(e -> {
                    log.error("❌ Failed to save chunks to Neo4j: {}", e.getMessage(), e);
                    throw new RuntimeException("Neo4j ingestion failed", e);
                });
    }


    /**
     * Given a question, extract entities and pull their relationships from Neo4j.
     */
    public Mono<String> queryRelevantEntities(String question, String documentId) {
        return entityExtractionService.extractEntities(question)
                .flatMapMany(Flux::fromIterable)
                .collectList()
                .flatMap(questionEntities -> {
                    if (questionEntities.isEmpty()) {
                        log.info("No entities found in question: {}", question);
                        return Mono.just("");
                    }

                    log.info("Found {} entities in question: {}",
                            questionEntities.size(),
                            questionEntities.stream().map(EntityInfo::getName).toList());

                    StringBuilder contextBuilder = new StringBuilder();

                    // Step 2: Collect entity context
                    for (EntityInfo entity : questionEntities) {
                        String entityContext = queryEntityContext(entity.getName(), documentId);
                        if (!entityContext.isEmpty()) {
                            contextBuilder.append(entityContext).append("\n\n");
                        }
                    }

                    // Step 3: Collect relationships between entities
                    String relationshipContext = queryEntityRelationships(questionEntities, documentId);
                    if (!relationshipContext.isEmpty()) {
                        contextBuilder.append("Relationships:\n").append(relationshipContext);
                    }

                    return Mono.just(contextBuilder.toString());
                })
                .onErrorResume(e -> {
                    log.error("❌ Failed to query entities from Neo4j: {}", e.getMessage(), e);
                    return Mono.just("");
                });
    }

    private void createDocumentNode(String documentId, String documentName, String topicId) {
        String cypher = """
            MERGE (d:Document {id: $documentId})
            SET d.name = $documentName, d.topicId = $topicId, d.createdAt = datetime()
            """;

        neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .bind(documentName).to("documentName")
                .bind(topicId).to("topicId")
                .run();
    }

    private void createChunkNode(String chunkId, String content, String documentId, String pageNo, int chunkIndex) {
        String cypher = """
            MERGE (c:Chunk {id: $chunkId})
            SET c.content = $content, c.pageNo = $pageNo, c.chunkIndex = $chunkIndex, c.createdAt = datetime()
            WITH c
            MATCH (d:Document {id: $documentId})
            MERGE (d)-[:HAS_CHUNK]->(c)
            """;

        neo4jClient.query(cypher)
                .bind(chunkId).to("chunkId")
                .bind(content).to("content")
                .bind(documentId).to("documentId")
                .bind(pageNo).to("pageNo")
                .bind(chunkIndex).to("chunkIndex")
                .run();
    }

    private void createEntityNode(EntityInfo entity) {
        String cypher = """
            MERGE (e:Entity {name: $name})
            SET e.type = $type, e.updatedAt = datetime()
            """;

        neo4jClient.query(cypher)
                .bind(entity.getName()).to("name")
                .bind(entity.getType()).to("type")
                .run();
    }

    private void createChunkEntityRelationship(String chunkId, String entityName, String entityType) {
        String cypher = """
            MATCH (c:Chunk {id: $chunkId})
            MATCH (e:Entity {name: $entityName})
            MERGE (c)-[r:MENTIONS]->(e)
            SET r.entityType = $entityType
            """;

        neo4jClient.query(cypher)
                .bind(chunkId).to("chunkId")
                .bind(entityName).to("entityName")
                .bind(entityType).to("entityType")
                .run();
    }

    private void createEntityRelationships(List<EntityInfo> entities, String chunkId) {
        // Create co-occurrence relationships between entities in the same chunk
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                EntityInfo entity1 = entities.get(i);
                EntityInfo entity2 = entities.get(j);

                String cypher = """
                    MATCH (e1:Entity {name: $entity1})
                    MATCH (e2:Entity {name: $entity2})
                    MERGE (e1)-[r:CO_OCCURS_WITH]-(e2)
                    SET r.chunkId = $chunkId, r.lastSeen = datetime()
                    """;

                neo4jClient.query(cypher)
                        .bind(entity1.getName()).to("entity1")
                        .bind(entity2.getName()).to("entity2")
                        .bind(chunkId).to("chunkId")
                        .run();
            }
        }
    }

    private String queryEntityContext(String entityName, String documentId) {
        String cypher = """
            MATCH (d:Document {id: $documentId})-[:HAS_CHUNK]->(c:Chunk)-[:MENTIONS]->(e:Entity {name: $entityName})
            RETURN c.content as content, c.pageNo as pageNo, c.chunkIndex as chunkIndex
            ORDER BY c.pageNo, c.chunkIndex
            LIMIT 5
            """;

        var result = neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .bind(entityName).to("entityName")
                .fetch().all();

        StringBuilder context = new StringBuilder();
        context.append("Entity '").append(entityName).append("' context:\n");

        for (var record : result) {
            String content = (String) record.get("content");
            String pageNo = (String) record.get("pageNo");
            // Fix casting issue - Neo4j returns Long, not Integer
            Number chunkIndexNumber = (Number) record.get("chunkIndex");
            int chunkIndex = chunkIndexNumber.intValue();

            context.append("Page ").append(pageNo).append(", Chunk ").append(chunkIndex)
                    .append(": ").append(content).append("\n");
        }

        return context.toString();
    }

    private String queryEntityRelationships(List<EntityInfo> entities, String documentId) {
        if (entities.size() < 2) return "";

        List<String> entityNames = entities.stream().map(EntityInfo::getName).toList();

        String cypher = """
            MATCH (d:Document {id: $documentId})-[:HAS_CHUNK]->(c:Chunk)
            WHERE any(entity IN $entityNames WHERE (c)-[:MENTIONS]->(:Entity {name: entity}))
            WITH c
            MATCH (c)-[:MENTIONS]->(e1:Entity)
            MATCH (c)-[:MENTIONS]->(e2:Entity)
            WHERE e1.name IN $entityNames AND e2.name IN $entityNames AND e1.name < e2.name
            RETURN DISTINCT e1.name as entity1, e2.name as entity2, collect(c.content) as contexts
            """;

        var result = neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .bind(entityNames).to("entityNames")
                .fetch().all();

        StringBuilder relationships = new StringBuilder();
        for (var record : result) {
            String entity1 = (String) record.get("entity1");
            String entity2 = (String) record.get("entity2");

            relationships.append(entity1).append(" is related to ").append(entity2)
                    .append(" in the document context.\n");
        }

        return relationships.toString();
    }
}