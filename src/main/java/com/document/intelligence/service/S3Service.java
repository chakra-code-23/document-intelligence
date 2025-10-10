package com.document.intelligence.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class S3Service {


    private final S3AsyncClient s3Client;

    public Mono<Void> upload(String bucket, String key, byte[] data) {
        log.info("Uploading file to S3 bucket={}, key={}, size={} bytes", bucket, key, data.length);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        AsyncRequestBody body = AsyncRequestBody.fromBytes(data);

        return Mono.fromFuture(() -> s3Client.putObject(request, body))
                .doOnSuccess(resp -> log.info("Upload completed for key={}", key))
                .doOnError(err -> log.error("Upload failed for key={}", key, err))
                .then();
    }

    public Mono<byte[]> download(String bucket, String key) {
        log.info("Downloading file from S3 bucket={}, key={}", bucket, key);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return Mono.fromFuture(
                        s3Client.getObject(request, AsyncResponseTransformer.toBytes())
                )
                .map(response -> {
                    byte[] data = response.asByteArray();
                    log.info("Downloaded {} bytes from S3 key={}", data.length, key);
                    return data;
                })
                .doOnError(err -> log.error("S3 download error for key={}: {}", key, err.getMessage(), err));
    }

    /**
     * Return Mono<Boolean> true if object exists (HEAD successful), false if 404 / NoSuchKey.
     * Propagates other errors.
     */
    public Mono<Boolean> headObjectExists(String bucket, String key) {
        HeadObjectRequest req = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        return Mono.fromFuture(s3Client.headObject(req))
                .map(resp -> true)
                .onErrorResume(throwable -> {
                    // If S3 returns 404/NoSuchKey, report false; otherwise propagate
                    if (throwable instanceof NoSuchKeyException) {
                        return Mono.just(false);
                    }
                    if (throwable instanceof S3Exception) {
                        S3Exception s3e = (S3Exception) throwable;
                        if (s3e.statusCode() == 404) return Mono.just(false);
                    }
                    return Mono.error(throwable);
                });
    }

}
