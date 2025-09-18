package com.document.intelligence.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LangChainOllamaService {

    private final ChatLanguageModel chatModel;

    public String askLangLlama(String question) {
        return chatModel.generate(question);
    }

}

