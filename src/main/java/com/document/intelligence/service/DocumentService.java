package com.document.intelligence.service;

import com.document.intelligence.dto.CompletionResponse;
import com.document.intelligence.dto.SplitResponse;
import com.document.intelligence.model.DocumentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final S3Service s3Service;
    private final DbService dbService;
    private final SplitPdfDocumentService splitPdfDocument;
    private final ParsingService parsingService;
    private final CompletionService completionService;
    private final IngestionService ingestionService;

    private static final String BUCKET = "chakradocumentbucket";

    public Mono<Map<String, Object>> processDocument(FilePart filePart, String topicName, String userId, String userName, String userEmail) {

        String documentId = UUID.randomUUID().toString();
        String topicId = UUID.randomUUID().toString();
        String s3Key = topicId + "/" + documentId + "/" + filePart.filename();

        log.info("Processing upload: documentId={}, topicId={}, fileName={}, userId={}",
                documentId, topicId, filePart.filename(), userId);

        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    log.info("Read {} bytes from uploaded file {}", bytes.length, filePart.filename());
                    return bytes;
                })
                .flatMap(bytes ->
                        // 1) Upload
                        s3Service.upload(BUCKET, s3Key, bytes)
                                // 2) Save metadata
                                .then(dbService.save(DocumentMetadata.builder()
                                        .userId(userId)
                                        .topicId(topicId)
                                        .topicName(topicName)
                                        .documentId(documentId)
                                        .documentName(filePart.filename())
                                        .userName(userName)
                                        .userEmail(userEmail)
                                        .ingestionStatus("IN_PROGRESS")
                                        .s3Key(s3Key)
                                        .build()
                                ))
                                .doOnSuccess(resp -> log.info("Metadata saved to DB for documentId={}", documentId))
                                // 3) WAIT until object is readable in S3 (polling)
                                .then(waitForObjectToBeVisible(BUCKET, s3Key, Duration.ofSeconds(15), Duration.ofMillis(500)))
                                // 4) Split after the object is visible
                                .then(Mono.defer(() -> splitPdfDocument.split(BUCKET, s3Key, documentId)))
                                // 5) Parse only if split succeeds
                                .flatMap(splitResponse -> parsingService.parseDocument(splitResponse)
                                        .doOnSuccess(parsed -> log.info("Parsing completed for documentId={}", documentId))
                                        .doOnError(err -> log.error("Parsing failed for documentId={}: {}", documentId, err.getMessage(), err))
                                )
                                // 6) After parsing → ingest → completion → combine both into Map
                                .flatMap(parsedResponse ->
                                        ingestionService.ingest(parsedResponse, filePart.filename(), topicId)  // <- return the reactive pipeline
                                                .doOnSuccess(v -> log.info("✅ Ingestion successful for documentId={}", parsedResponse.getDocumentId()))
                                                .doOnError(err -> log.error("❌ Ingestion failed for documentId={}: {}", parsedResponse.getDocumentId(), err.getMessage(), err))
                                                .then(
                                                        completionService.generateCompletion(parsedResponse, topicId, parsedResponse.getDocumentId())
                                                                .map(completion -> {
                                                                    Map<String, Object> response = new HashMap<>();
                                                                    response.put("documentId", parsedResponse.getDocumentId());
                                                                    response.put("topicId", topicId);
                                                                    response.put("parsedChunks", parsedResponse.getPages());
                                                                    response.put("summary", completion.getSummary());
                                                                    response.put("questions", completion.getQuestions());
                                                                    return response;
                                                                })
                                                )
                                                .doOnSuccess(comp -> log.info("✅ Completion + Parsed chunks combined for documentId={}", parsedResponse.getDocumentId()))
                                                .doOnError(err -> log.error("❌ Failed combining completion + parsed chunks for documentId={}: {}", parsedResponse.getDocumentId(), err.getMessage(), err))
                                )

                )
                .doOnError(err -> log.error("processDocument failed for documentId={}: {}", documentId, err.getMessage(), err));
    }


    /**
     * Poll headObjectExists until true or timeout.
     * - timeout : overall timeout
     * - pollInterval : how often to poll
     */
    private Mono<Void> waitForObjectToBeVisible(String bucket, String key, Duration timeout, Duration pollInterval) {
        return Flux.interval(Duration.ZERO, pollInterval)
                .flatMap(i -> s3Service.headObjectExists(bucket, key))
                .filter(Boolean::booleanValue)      // pass only when true
                .next()                             // take first true
                .switchIfEmpty(Mono.error(new RuntimeException("S3 object not visible within timeout")))
                .timeout(timeout)
                .then(); // return Mono<Void> when visible
    }

  /*
    **** THIS IS JUST FOR RECEIVING THE PARSED RESPONSE ****
    *
    public Mono<SplitResponse> processDocumentParsed(FilePart filePart,
                                               String topicName,
                                               String userId,
                                               String userName,
                                               String userEmail) {

        String documentId = UUID.randomUUID().toString();
        String topicId = UUID.randomUUID().toString();
        String s3Key = topicId + "/" + documentId + "/" + filePart.filename();

        log.info("Processing upload: documentId={}, topicId={}, fileName={}, userId={}",
                documentId, topicId, filePart.filename(), userId);

        return DataBufferUtils.join(filePart.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    log.info("Read {} bytes from uploaded file {}", bytes.length, filePart.filename());
                    return bytes;
                })
                .flatMap(bytes ->
                        // 1) Upload
                        s3Service.upload(BUCKET, s3Key, bytes)
                                // 2) Save metadata
                                .then(dbService.save(DocumentMetadata.builder()
                                        .userId(userId)
                                        .topicId(topicId)
                                        .topicName(topicName)
                                        .documentId(documentId)
                                        .documentName(filePart.filename())
                                        .userName(userName)
                                        .userEmail(userEmail)
                                        .ingestionStatus("IN_PROGRESS")
                                        .s3Key(s3Key)
                                        .build()
                                ))
                                .doOnSuccess(resp -> log.info("Metadata saved to DB for documentId={}", documentId))
                                // 3) WAIT until object is readable in S3 (polling)
                                .then(waitForObjectToBeVisible(BUCKET, s3Key, Duration.ofSeconds(15), Duration.ofMillis(500)))
                                // 4) Split after the object is visible
                                .then(Mono.defer(() -> splitPdfDocument.split(BUCKET, s3Key, documentId)))
                                // 5) Only after split succeeds, parse with LLM
                                .flatMap(splitResponse -> parsingService.parseDocument(splitResponse)
                                        .doOnSuccess(parsed -> log.info("Parsing completed for documentId={}", documentId))
                                        .doOnError(err -> log.error("Parsing failed for documentId={}: {}", documentId, err.getMessage(), err))
                                )
                )
                .doOnError(err -> log.error("processDocument failed for documentId={}: {}", documentId, err.getMessage(), err));
    } */

}
