package com.document.intelligence.handler;

import com.document.intelligence.dto.GetRequest;
import com.document.intelligence.model.DocumentMetadata;
import com.document.intelligence.service.DbService;
import com.document.intelligence.service.PineconeVectorService;
import com.document.intelligence.service.S3Service;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class TestHandler {


    private final S3Service s3Service;
    private final DbService service;
    private final PineconeVectorService pineconeVectorService;

    public TestHandler(S3Service s3Service, DbService service, PineconeVectorService pineconeVectorService) {
        this.s3Service = s3Service;
        this.service = service;
        this.pineconeVectorService = pineconeVectorService;
    }

    public Mono<ServerResponse> upload(ServerRequest request) {
        return request.multipartData()
                .flatMap(parts -> {
                    FilePart filePart = (FilePart) parts.toSingleValueMap().get("file");
                    Flux<DataBuffer> content = filePart.content();

                    return content
                            .reduce(new ByteArrayOutputStream(), (baos, dataBuffer) -> {
                                try {
                                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                    dataBuffer.read(bytes);
                                    baos.write(bytes);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                return baos;
                            })
                            .flatMap(baos -> {
                                byte[] bytes = baos.toByteArray();
                                return s3Service.upload("chakradocumentbucket", filePart.filename(), bytes);
                            });
                })
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> insert(ServerRequest request) {
        return request.bodyToMono(DocumentMetadata.class)
                .flatMap(service::save)
                .flatMap(msg -> ServerResponse.ok().bodyValue(msg));
    }


    public Mono<ServerResponse> getDocument(ServerRequest request) {
        return request.bodyToMono(GetRequest.class)
                .flatMap(req -> service.getItem(req.getDocumentId(), req.getTopicId()))
                .flatMap(item -> ServerResponse.ok().bodyValue(item))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> testPinecone(ServerRequest request) {
        return request.bodyToMono(String.class)
                .flatMap(text -> {
                    String result = pineconeVectorService.saveText(text);
                    return ServerResponse.ok().bodyValue(result);
                });
    }

}
