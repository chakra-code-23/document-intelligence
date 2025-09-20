package com.document.intelligence.service;


import com.document.intelligence.dto.EntityInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.core.ReactiveNeo4jClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class Neo4jGraphService {

    private final ReactiveNeo4jClient neo4jClient;
    private final EntityExtractionService entityExtractionService;

    // Add this method to Neo4jGraphService
    private Mono<Void> ensureDocumentExists(String documentId) {
        String cypher = """
        MERGE (d:Document {id: $documentId})
        SET d.createdAt = coalesce(d.createdAt, datetime()),
            d.updatedAt = datetime()
        """;

        return neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .run()
                .then()
                .doOnSuccess(v -> log.debug("Neo4j: Ensured Document node exists for id={}", documentId));
    }

    // Update your saveChunksToNeo4j method to create Document first
    public Mono<Void> saveChunksToNeo4j(List<String> chunks, Map<String, String> metadata) {
        String documentId = metadata.get("documentId");
        String pageNo = metadata.get("pageNo");

        // STEP 1: Ensure Document node exists FIRST
        return ensureDocumentExists(documentId)
                .then(
                        Flux.fromIterable(chunks)
                                .index()
                                .flatMapSequential(tuple -> {
                                    long i = tuple.getT1();
                                    String chunk = tuple.getT2();
                                    String chunkId = documentId + "_p" + pageNo + "_c" + i;

                                    return createChunkNode(chunkId, chunk, documentId, pageNo, (int) i)
                                            .then(entityExtractionService.extractEntities(chunk))
                                            .flatMapMany(Flux::fromIterable)
                                            .filter(e -> e != null &&
                                                    e.getName() != null && !e.getName().trim().isEmpty() &&
                                                    e.getType() != null && !e.getType().trim().isEmpty())
                                            .collectList()
                                            .flatMap(entities -> {
                                                if (entities.isEmpty()) return Mono.empty();

                                                return createEntitiesAndRelationshipsBatched(chunkId, entities)
                                                        .then(createEntityRelationshipsBatched(chunkId, entities));
                                            });
                                }, /* concurrency */ 5)
                                .then()
                )
                .doOnSuccess(v -> log.info("✅ Saved {} chunks + entities into Neo4j for docId={} page={}", chunks.size(), documentId, pageNo))
                .doOnError(e -> {
                    log.error("❌ Failed to save chunks/entities to Neo4j: {}", e.getMessage(), e);
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

                    // Step 2: Collect entity contexts reactively
                    Mono<String> contextsMono = Flux.fromIterable(questionEntities)
                            .flatMap(entity -> queryEntityContext(entity.getName(), documentId)) // Mono<String> per entity
                            .collectList()
                            .map(contexts -> String.join("\n\n", contexts));

                    // Step 3: Collect relationships reactively
                    Mono<String> relationshipsMono = queryEntityRelationships(questionEntities, documentId);

                    // Combine both
                    return Mono.zip(contextsMono, relationshipsMono)
                            .map(tuple -> {
                                String contexts = tuple.getT1();
                                String relationships = tuple.getT2();

                                StringBuilder sb = new StringBuilder();
                                if (!contexts.isEmpty()) {
                                    sb.append(contexts).append("\n\n");
                                }
                                if (!relationships.isEmpty()) {
                                    sb.append("Relationships:\n").append(relationships);
                                }
                                return sb.toString();
                            });
                })
                .onErrorResume(e -> {
                    log.error("❌ Failed to query entities from Neo4j: {}", e.getMessage(), e);
                    return Mono.just("");
                });
    }

    private Mono<Void> createChunkNode(String chunkId, String content, String documentId, String pageNo, int chunkIndex) {
        String cypher = """
        MERGE (c:Chunk {id: $chunkId})
        SET c.content = $content, c.pageNo = $pageNo, c.chunkIndex = $chunkIndex, c.createdAt = datetime()
        WITH c
        MATCH (d:Document {id: $documentId})
        MERGE (d)-[:HAS_CHUNK]->(c)
        """;

        return neo4jClient.query(cypher)
                .bind(chunkId).to("chunkId")
                .bind(content).to("content")
                .bind(documentId).to("documentId")
                .bind(pageNo).to("pageNo")
                .bind(chunkIndex).to("chunkIndex")
                .run()
                .then()
                .doOnSuccess(v -> log.debug("Neo4j: Created Chunk node id={} for docId={} pageNo={}", chunkId, documentId, pageNo));
    }


    private Mono<String> queryEntityContext(String entityName, String documentId) {
        String cypher = """
        MATCH (d:Document {id: $documentId})-[:HAS_CHUNK]->(c:Chunk)-[:MENTIONS]->(e:Entity {name: $entityName})
        RETURN c.content as content, c.pageNo as pageNo, c.chunkIndex as chunkIndex
        ORDER BY c.pageNo, c.chunkIndex
        LIMIT 5
        """;

        return neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .bind(entityName).to("entityName")
                .fetch()
                .all()
                .collectList()
                .map(records -> {
                    StringBuilder context = new StringBuilder();
                    context.append("Entity '").append(entityName).append("' context:\n");

                    for (var record : records) {
                        String content = (String) record.get("content");
                        String pageNo = (String) record.get("pageNo");

                        Number chunkIndexNumber = (Number) record.get("chunkIndex");
                        int chunkIndex = chunkIndexNumber.intValue();

                        context.append("Page ").append(pageNo).append(", Chunk ").append(chunkIndex)
                                .append(": ").append(content).append("\n");
                    }

                    return context.toString();
                });
    }

    private Mono<String> queryEntityRelationships(List<EntityInfo> entities, String documentId) {
        if (entities.size() < 2) {
            return Mono.just("");
        }

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

        return neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .bind(entityNames).to("entityNames")
                .fetch()
                .all()
                .collectList()
                .map(records -> {
                    StringBuilder relationships = new StringBuilder();
                    for (var record : records) {
                        String entity1 = (String) record.get("entity1");
                        String entity2 = (String) record.get("entity2");

                        relationships.append(entity1)
                                .append(" is related to ")
                                .append(entity2)
                                .append(" in the document context.\n");
                    }
                    return relationships.toString();
                });
    }



    /**
     * Batch insert entity nodes + relationships to chunk
     */
    private Mono<Void> createEntitiesAndRelationshipsBatched(String chunkId, List<EntityInfo> entities) {
        if (entities.isEmpty()) return Mono.empty();

        return Flux.fromIterable(entities)
                .map(e -> Map.of("name", e.getName(), "type", e.getType()))
                .buffer(20) // batch 20 entities per query
                .concatMap(batch -> {
                    String cypher = """
                    UNWIND $entities AS entity
                    MERGE (e:Entity {name: entity.name})
                    SET e.type = entity.type, e.updatedAt = datetime()
                    WITH e, entity
                    MATCH (c:Chunk {id: $chunkId})
                    MERGE (c)-[:MENTIONS {entityType: entity.type}]->(e)
                """;

                    return neo4jClient.query(cypher)
                            .bind(chunkId).to("chunkId")
                            .bind(batch).to("entities")
                            .run()
                            .then()
                            .doOnSuccess(v -> log.debug("Neo4j: Batch created {} entities+MENTIONS for chunk {}", batch.size(), chunkId));
                })
                .then();
    }

    /**
     * Batch create co-occurrence relationships between entities in the same chunk
     */
    private Mono<Void> createEntityRelationshipsBatched(String chunkId, List<EntityInfo> entities) {
        if (entities.size() < 2) return Mono.empty();

        List<Map<String, Object>> rels = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                rels.add(Map.of(
                        "entity1", entities.get(i).getName(),
                        "entity2", entities.get(j).getName(),
                        "chunkId", chunkId
                ));
            }
        }

        return Flux.fromIterable(rels)
                .buffer(20) // batch 20 relationships per query
                .concatMap(batch -> {
                    String cypher = """
                    UNWIND $rels AS rel
                    MATCH (e1:Entity {name: rel.entity1})
                    MATCH (e2:Entity {name: rel.entity2})
                    MERGE (e1)-[r:CO_OCCURS_WITH]-(e2)
                    SET r.chunkId = rel.chunkId, r.lastSeen = datetime()
                """;

                    return neo4jClient.query(cypher)
                            .bind(batch).to("rels")
                            .run()
                            .then()
                            .doOnSuccess(v -> log.debug("Neo4j: Batch created {} co-occurrence relationships in chunk {}", batch.size(), chunkId));
                })
                .then();
    }
    // Add this method to Neo4jGraphService
    public void debugDatabaseContent(String documentId) {
        log.info("=== STEP 1: DEBUGGING DATABASE CONTENT ===");
        if (documentId != null) {
            log.info("Focusing on document ID: {}", documentId);
        }

        // Check what documents exist
        String docCypher = """
        MATCH (d:Document)
        OPTIONAL MATCH (d)-[:HAS_CHUNK]->(c:Chunk)
        RETURN d.id as docId, count(c) as chunkCount
        """;

        neo4jClient.query(docCypher)
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("📄 DOCUMENTS IN DATABASE:");
                    records.forEach(record ->
                            log.info("  - Document ID: '{}' with {} chunks",
                                    record.get("docId"), record.get("chunkCount")));
                })
                .then()
                .then(documentId != null ? checkSpecificDocument(documentId) : Mono.empty())
                .then(checkAllEntities())
                .then(documentId != null ? checkMentionsRelationships(documentId) : checkAllMentionsRelationships())
                .subscribe(
                        v -> log.info("=== STEP 1 DEBUG COMPLETED ==="),
                        error -> log.error("=== STEP 1 DEBUG FAILED ===", error)
                );
    }

    private Mono<Void> checkSpecificDocument(String documentId) {
        String cypher = """
        MATCH (d:Document {id: $documentId})-[:HAS_CHUNK]->(c:Chunk)
        RETURN c.id as chunkId, c.content as content
        ORDER BY c.chunkIndex
        LIMIT 5
        """;

        return neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("📁 CHUNKS FOR DOCUMENT '{}':", documentId);
                    if (records.isEmpty()) {
                        log.warn("  ❌ NO CHUNKS FOUND for this document ID");
                    } else {
                        records.forEach(record -> {
                            String content = (String) record.get("content");
                            String preview = content.length() > 100 ?
                                    content.substring(0, 100) + "..." : content;
                            log.info("  - Chunk: {} | Content: {}",
                                    record.get("chunkId"), preview);
                        });
                    }
                })
                .then();
    }

    private Mono<Void> checkAllMentionsRelationships() {
        String cypher = """
        MATCH (c:Chunk)-[:MENTIONS]->(e:Entity)
        RETURN c.id as chunkId, e.name as entityName, e.type as entityType
        LIMIT 20
        """;

        return neo4jClient.query(cypher)
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("🔗 ALL MENTIONS RELATIONSHIPS:");
                    if (records.isEmpty()) {
                        log.warn("  ❌ NO MENTIONS relationships found in entire database");
                    } else {
                        records.forEach(record ->
                                log.info("  - Chunk {} MENTIONS Entity: '{}' ({})",
                                        record.get("chunkId"),
                                        record.get("entityName"),
                                        record.get("entityType")));
                    }
                })
                .then();
    }

    private Mono<Void> checkAllEntities() {
        String cypher = "MATCH (e:Entity) RETURN e.name as name, e.type as type LIMIT 20";

        return neo4jClient.query(cypher)
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("🏷️  ALL ENTITIES IN DATABASE:");
                    if (records.isEmpty()) {
                        log.warn("  ❌ NO ENTITIES FOUND in database");
                    } else {
                        records.forEach(record ->
                                log.info("  - Entity: '{}' | Type: {}",
                                        record.get("name"), record.get("type")));
                    }
                })
                .then();
    }

    private Mono<Void> checkMentionsRelationships(String documentId) {
        String cypher = """
        MATCH (d:Document {id: $documentId})-[:HAS_CHUNK]->(c:Chunk)-[:MENTIONS]->(e:Entity)
        RETURN c.id as chunkId, e.name as entityName, e.type as entityType
        LIMIT 20
        """;

        return neo4jClient.query(cypher)
                .bind(documentId).to("documentId")
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("🔗 MENTIONS RELATIONSHIPS FOR DOCUMENT '{}':", documentId);
                    if (records.isEmpty()) {
                        log.warn("  ❌ NO MENTIONS relationships found");
                    } else {
                        records.forEach(record ->
                                log.info("  - Chunk {} MENTIONS Entity: '{}' ({})",
                                        record.get("chunkId"),
                                        record.get("entityName"),
                                        record.get("entityType")));
                    }
                })
                .then();
    }

    public void findSriVenkateswaraContent() {
        log.info("=== STEP 2: FINDING SRI VENKATESWARA CONTENT ===");

        String cypher = """
        MATCH (d:Document)-[:HAS_CHUNK]->(c:Chunk)
        WHERE toLower(c.content) CONTAINS 'venkateswara' 
           OR toLower(c.content) CONTAINS 'lakshmi' 
           OR toLower(c.content) CONTAINS 'padmavathi'
        RETURN d.id as docId, c.id as chunkId, c.content as content
        LIMIT 5
        """;

        neo4jClient.query(cypher)
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("🔍 SEARCHING FOR SRI VENKATESWARA CONTENT:");
                    if (records.isEmpty()) {
                        log.warn("❌ No chunks found containing Venkateswara, Lakshmi, or Padmavathi");
                        log.info("Let's check a few random chunks to see what content exists...");
                        checkRandomChunks();
                    } else {
                        records.forEach(record -> {
                            String docId = (String) record.get("docId");
                            String chunkId = (String) record.get("chunkId");
                            String content = (String) record.get("content");
                            String preview = content.length() > 200 ?
                                    content.substring(0, 200) + "..." : content;

                            log.info("✅ FOUND in Document: {}", docId);
                            log.info("   Chunk: {}", chunkId);
                            log.info("   Content: {}", preview);
                            log.info("   ---");
                        });
                    }
                })
                .subscribe(
                        v -> log.info("=== STEP 2 SEARCH COMPLETED ==="),
                        error -> log.error("=== STEP 2 SEARCH FAILED ===", error)
                );
    }

    private void checkRandomChunks() {
        String cypher = """
        MATCH (d:Document)-[:HAS_CHUNK]->(c:Chunk)
        RETURN d.id as docId, c.content as content
        LIMIT 3
        """;

        neo4jClient.query(cypher)
                .fetch()
                .all()
                .collectList()
                .doOnNext(records -> {
                    log.info("📝 SAMPLE CHUNKS FROM DATABASE:");
                    records.forEach(record -> {
                        String docId = (String) record.get("docId");
                        String content = (String) record.get("content");
                        String preview = content.length() > 150 ?
                                content.substring(0, 150) + "..." : content;

                        log.info("   Document: {} | Content: {}", docId, preview);
                    });
                })
                .subscribe();
    }

}
