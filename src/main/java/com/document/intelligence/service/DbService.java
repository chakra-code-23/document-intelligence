package com.document.intelligence.service;

import com.document.intelligence.model.DocumentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DbService {

    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    //TESTING DB OPERATION
    public Mono<String> save(DocumentMetadata metadata) {
        log.info("Saving metadata to DynamoDB: documentId={}, topicId={}", metadata.getDocumentId(), metadata.getTopicId());

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(metadata.getUserId()).build());
        item.put("topicId", AttributeValue.builder().s(metadata.getTopicId()).build());
        item.put("topicName", AttributeValue.builder().s(metadata.getTopicName()).build());
        item.put("documentId", AttributeValue.builder().s(metadata.getDocumentId()).build());
        item.put("documentName", AttributeValue.builder().s(metadata.getDocumentName()).build());
        item.put("userName", AttributeValue.builder().s(metadata.getUserName()).build());
        item.put("userEmail", AttributeValue.builder().s(metadata.getUserEmail()).build());
        item.put("ingestionStatus", AttributeValue.builder().s(metadata.getIngestionStatus()).build());
        item.put("s3Key", AttributeValue.builder().s(metadata.getS3Key()).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName("document_metadata")
                .item(item)
                .build();

        return Mono.fromFuture(dynamoDbAsyncClient.putItem(request))
                .doOnSuccess(resp -> log.info("Metadata saved successfully for documentId={}", metadata.getDocumentId()))
                .doOnError(err -> log.error("Failed to save metadata to DynamoDB: {}", err.getMessage(), err))
                .thenReturn("Inserted Successfully");
    }

    public Mono<DocumentMetadata> getItem(String documentId, String topicId) {
        Map<String, AttributeValue> key = Map.of(
                "documentId", AttributeValue.builder().s(documentId).build(),
                "topicId", AttributeValue.builder().s(topicId).build()
        );

        GetItemRequest request = GetItemRequest.builder()
                .tableName("document_metadata")
                .key(key)
                .build();

        return Mono.fromFuture(() -> dynamoDbAsyncClient.getItem(request))
                .map(GetItemResponse::item)
                .filter(map -> !map.isEmpty())
                .map(this::mapToDocumentMetadata);
    }

    private DocumentMetadata mapToDocumentMetadata(Map<String, AttributeValue> item) {
        return DocumentMetadata.builder()
                .userId(item.get("userId").s())
                .topicId(item.get("topicId").s())
                .topicName(item.get("topicName").s())
                .documentId(item.get("documentId").s())
                .documentName(item.get("documentName").s())
                .userName(item.get("userName").s())
                .userEmail(item.get("userEmail").s())
                .ingestionStatus(item.get("ingestionStatus").s())
                .build();
    }

    public Mono<Void> updateDocumentMetadata(String topicId, String documentId, String summary, List<String> questions) {
        return Mono.fromRunnable(() -> {
            try {
                UpdateItemRequest request = UpdateItemRequest.builder()
                        .tableName("document_metadata")
                        .key(Map.of(
                                "topicId", AttributeValue.builder().s(topicId).build(),
                                "documentId", AttributeValue.builder().s(documentId).build()
                        ))
                        .updateExpression("SET summary = :summary, questions = :questions")
                        .expressionAttributeValues(Map.of(
                                ":summary", AttributeValue.builder().s(summary).build(),
                                ":questions", AttributeValue.builder().ss(questions).build()
                        ))
                        .build();

                dynamoDbAsyncClient.updateItem(request);
            } catch (Exception e) {
                throw new RuntimeException("Failed to update metadata for documentId=" + documentId, e);
            }
        });
    }



}
