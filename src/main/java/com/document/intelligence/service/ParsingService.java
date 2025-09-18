package com.document.intelligence.service;

import com.document.intelligence.dto.PageChunks;
import com.document.intelligence.dto.SplitResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
@Service
@RequiredArgsConstructor
@Slf4j
public class ParsingService {

    private final LangChainOllamaService llmService;
    private final ClaudeApiService claudeApiService;

    public Mono<SplitResponse> parseDocument(SplitResponse splitResponse) {
        log.info("Starting parsing with LLM for documentId={}", splitResponse.getDocumentId());

        List<PageChunks> parsedPages = new ArrayList<>();

        for (PageChunks page : splitResponse.getPages()) {
            log.info("🔹 Parsing started for documentId={}, page={}/{}",
                    splitResponse.getDocumentId(), page.getPageNo(), page.getTotalPages());

            List<String> parsedChunks = new ArrayList<>();

            for (int i = 0; i < page.getChunks().size(); i++) {
                String chunk = page.getChunks().get(i);

                // Escape quotes inside the chunk
                String safeChunk = chunk.replace("\"", "\\\"");

                String prompt =
                                "You are a precise text extraction and translation engine. " +
                                "From the following chunk, return ONLY clean, meaningful English text. " +
                                "If there is any non-English text (such as Sanskrit, Hindi, verses, or symbols), " +
                                "translate it into natural English and replace it. " +
                                "Do not include the original non-English words. " +
                                "Do not add explanations, notes, or comments about what was skipped. " +
                                "Return only the final English text, nothing else.\n\n" +
                                "\"" + safeChunk + "\"";

                try {
                    log.debug("Sending chunk {} of page {} to LLM (documentId={})",
                            i + 1, page.getPageNo(), splitResponse.getDocumentId());

                    String parsed = claudeApiService.callClaude(prompt);

                    parsedChunks.add(parsed != null ? parsed : "");

                    log.debug("✅ Parsed chunk {} of page {} successfully (documentId={})",
                            i + 1, page.getPageNo(), splitResponse.getDocumentId());

                } catch (Exception e) {
                    log.error("❌ LLM parsing failed for documentId={}, page={}, chunk={}",
                            splitResponse.getDocumentId(), page.getPageNo(), i + 1, e);
                    parsedChunks.add(""); // keep empty if failed
                }
            }

            parsedPages.add(new PageChunks(page.getPageNo(), page.getTotalPages(), parsedChunks));

            log.info("✅ Parsing completed for documentId={}, page={}/{}",
                    splitResponse.getDocumentId(), page.getPageNo(), page.getTotalPages());
        }

        log.info("🎯 Completed parsing all pages for documentId={}", splitResponse.getDocumentId());

        return Mono.just(new SplitResponse(splitResponse.getDocumentId(), parsedPages));
    }
}
