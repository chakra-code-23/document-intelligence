package com.document.intelligence.configuration;

import com.document.intelligence.handler.DocumentHandler;
import com.document.intelligence.handler.QaHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

@Configuration
@RequiredArgsConstructor
public class DocumentRouters {

    private final DocumentHandler documentHandler;

    @Bean
    public RouterFunction<ServerResponse> route() {
        return RouterFunctions.route()
                .POST("/documents/upload", documentHandler::uploadDocument)
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> qaRoutes(QaHandler qaHandler) {
        return RouterFunctions.route(POST("/qa/ask"), qaHandler::askQuestion);
    }
}
