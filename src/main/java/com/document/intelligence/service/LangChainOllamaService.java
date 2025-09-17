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

    /**
     * RAG-style answer generation using retrieved context
     */
    public String generateAnswer(String question, List<String> context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful assistant. Use the following context to answer the question.\n\n");

        for (int i = 0; i < context.size(); i++) {
            prompt.append("Context ").append(i + 1).append(": ").append(context.get(i)).append("\n");
        }

        prompt.append("\nQuestion: ").append(question).append("\n");
        prompt.append("Answer in clear and concise English.");

        // Use UserMessage so we have flexibility
        Response<AiMessage> response = chatModel.generate(List.of(UserMessage.from(prompt.toString())));

        return response.content().text();
    }

}

