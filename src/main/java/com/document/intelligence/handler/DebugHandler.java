package com.document.intelligence.handler;

import com.document.intelligence.service.Neo4jGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class DebugHandler {

    private final Neo4jGraphService graphService;

    public Mono<ServerResponse> debugDatabase(ServerRequest request) {
        String documentId = request.pathVariable("documentId");

        log.info("🔍 Starting database debug for documentId: {}", documentId);

        // Trigger the debug (it runs async and logs results)
        graphService.debugDatabaseContent(documentId);

        return ServerResponse.ok()
                .bodyValue("Debug started for document: " + documentId +
                        " - check your application logs for results");
    }

    public Mono<ServerResponse> debugAllDatabase(ServerRequest request) {
        log.info("🔍 Starting full database debug");

        // Debug without specific document ID to see everything
        graphService.debugDatabaseContent(null);

        return ServerResponse.ok()
                .bodyValue("Full database debug started - check your application logs");
    }

    public Mono<ServerResponse> findSriVenkateswaraContent(ServerRequest request) {
        log.info("🔍 Searching for Sri Venkateswara content");

        graphService.findSriVenkateswaraContent();

        return ServerResponse.ok()
                .bodyValue("Searching for Sri Venkateswara content - check your application logs");
    }
}