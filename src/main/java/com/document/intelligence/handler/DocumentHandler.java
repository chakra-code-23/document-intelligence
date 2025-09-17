package com.document.intelligence.handler;

import com.document.intelligence.model.DocumentMetadata;
import com.document.intelligence.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DocumentHandler {

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    public Mono<ServerResponse> uploadDocument(ServerRequest request) {
        return request.multipartData()
                .flatMap(parts -> {
                    Map<String, Part> map = parts.toSingleValueMap();

                    // File part
                    FilePart file = (FilePart) map.get("file");

                    // Metadata JSON part
                    String metadataJson = ((FormFieldPart) map.get("metadata")).value();

                    // Convert JSON to POJO
                    DocumentMetadata metadata;
                    try {
                        metadata = objectMapper.readValue(metadataJson, DocumentMetadata.class);
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("Invalid metadata JSON", e));
                    }

                    // Call service with metadata fields
                    return documentService.processDocument(
                            file,
                            metadata.getTopicName(),
                            metadata.getUserId(),
                            metadata.getUserName(),
                            metadata.getUserEmail()
                    );
                })
                .flatMap(resp -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(resp))
                .onErrorResume(e ->
                        ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("error", e.getMessage())));
    }
}
