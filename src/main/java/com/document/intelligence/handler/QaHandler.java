package com.document.intelligence.handler;

import com.document.intelligence.dto.AnswerResponse;
import com.document.intelligence.dto.QuestionRequest;
import com.document.intelligence.service.KagOrRagRouterService;
import com.document.intelligence.service.QaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class QaHandler {

    private final QaService qaService;
    private final KagOrRagRouterService kagOrRagRouterService;

    public Mono<ServerResponse> askQuestion(ServerRequest request) {
        return request.bodyToMono(QuestionRequest.class)
                .flatMap(kagOrRagRouterService::route) // returns Mono<AnswerResponse>
                .flatMap(answer ->
                        ServerResponse.ok()
                                .body(Mono.just(answer), AnswerResponse.class) // ✅ correct
                )
                .onErrorResume(err -> {
                    log.error("Error while processing QA: {}", err.getMessage(), err);
                    return ServerResponse.status(500)
                            .body(Mono.just(new AnswerResponse("Failed to generate answer", List.of())), AnswerResponse.class);
                });
    }

}
