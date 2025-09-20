package com.document.intelligence.configuration;


import com.document.intelligence.handler.DebugHandler;
import com.document.intelligence.handler.TestHandler;
import com.document.intelligence.service.ClaudeApiService;
import com.document.intelligence.service.LangChainOllamaService;
import com.document.intelligence.service.LlmService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class TestRouter {


    @Bean
    public RouterFunction<ServerResponse> aiRoutes(LlmService aiService) {
        return route()
                .GET("/api/test-ai", req -> {
                    String response = aiService.askLlama("Who is Arjuna?");
                    return ServerResponse.ok().bodyValue(response);
                })
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> langChainLlm(LangChainOllamaService aiService) {
        return route()
                .GET("/api/test/llm", req -> {
                    String response = aiService.askLangLlama("Who is Arjuna?");
                    return ServerResponse.ok().bodyValue(response);
                })
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> routes(TestHandler handler) {
        return route(POST("/files/upload"), handler::upload);
    }

    @Bean
    public RouterFunction<ServerResponse> docRoutes(TestHandler handler) {
        return route()
                .POST("/dynamo/insert", handler::insert)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> dbGet(TestHandler testHandler) {
        return route()
                .POST("/details", testHandler::getDocument)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> pineconeRoutes(TestHandler testHandler) {
        return route()
                .POST("/api/test-pinecone", testHandler::testPinecone)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> claudeTest(ClaudeApiService claudeApiService) {
        return route()
                .GET("/api/test-claude", req -> {
                    String prompt = req.queryParam("prompt").orElse("Say hello from Claude API!");

                    return claudeApiService.callClaudeReactive(prompt)
                            .flatMap(response -> ServerResponse.ok().bodyValue(Map.of(
                                    "status", "success",
                                    "prompt", prompt,
                                    "response", response,
                                    "timestamp", System.currentTimeMillis()
                            )))
                            .onErrorResume(error -> ServerResponse.status(500).bodyValue(Map.of(
                                    "status", "error",
                                    "message", error.getMessage(),
                                    "timestamp", System.currentTimeMillis()
                            )));
                })
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> debugRoutes(DebugHandler debugHandler) {
        return RouterFunctions
                .route(GET("/api/debug/database/{documentId}"), debugHandler::debugDatabase)
                .andRoute(GET("/api/debug/database"), debugHandler::debugAllDatabase)
                .andRoute(GET("/api/debug/find-venkateswara"), debugHandler::findSriVenkateswaraContent);
    }

}