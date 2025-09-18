package com.document.intelligence.service;

import com.document.intelligence.dto.CompletionResponse;
import com.document.intelligence.dto.SplitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompletionService {

    private final LangChainOllamaService llmService;
    private final DbService dbService;
    private final ClaudeApiService claudeApiService;

    public Mono<CompletionResponse> generateCompletion(SplitResponse splitResponse, String topicId, String documentId) {
        log.info("Starting completion for documentId={}", documentId);

        // Merge all parsed chunks
        String fullContent = splitResponse.getPages().stream()
                .flatMap(p -> p.getChunks().stream())
                .collect(Collectors.joining(" "));

        // Prompts
        String summaryPrompt =
                "Summarize the following content in clear English withing 150 words. " +
                        "Return only the summary text, nothing else:\n\n\"" + fullContent + "\"";

        String questionsPrompt =
                "Generate 5 thoughtful sample questions that could be asked based on the following content. " +
                        "Return only the questions as a plain list:\n\n\"" + fullContent + "\"";

        return Mono.fromCallable(() -> {

                    log.info("Calling LLM to extract summary for documentId={}", documentId);

                    String summary = claudeApiService.callClaude(summaryPrompt);

                    log.info("Summary extracted successfully for documentId={}", documentId);

                    if (summary != null) {
                        summary = summary
                                .replace("\n\n", " ")   // remove double newlines first
                                .replace("\n", " ")     // then single newlines
                                .replaceAll("\\s+", " ")// collapse multiple spaces
                                .trim();

                        log.info("Summary Trimmed successfully for documentId={}", documentId);
                    }

                    log.info("Calling LLM to generate questions for documentId={}", documentId);

                    String questionsRaw = claudeApiService.callClaude(questionsPrompt);

                    log.info("Questions generated successfully for documentId={}", documentId);

                    List<String> questions = Arrays.stream(questionsRaw.split("\n"))
                            .map(String::trim)
                            .filter(q -> !q.isEmpty())
                            .collect(Collectors.toList());

                    return new CompletionResponse(documentId, topicId, summary, questions);
                })
                .flatMap(resp ->
                        dbService.updateDocumentMetadata(topicId, documentId, resp.getSummary(), resp.getQuestions())
                                .thenReturn(resp)
                )
                .doOnSuccess(resp -> log.info("Completion stored in DB for documentId={}", documentId))
                .doOnError(err -> log.error("Completion failed for documentId={}: {}", documentId, err.getMessage(), err));
    }
}
