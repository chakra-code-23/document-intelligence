package com.document.intelligence.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class LlmService {

    private final ChatLanguageModel chatModel;

    public String askLlama(String question) {
        return chatModel.generate(question);
    }

}
