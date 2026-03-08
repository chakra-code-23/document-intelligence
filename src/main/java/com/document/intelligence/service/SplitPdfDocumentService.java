package com.document.intelligence.service;

import com.document.intelligence.dto.PageChunks;
import com.document.intelligence.dto.SplitResponse;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SplitPdfDocumentService {

    private final S3Service s3Service;


    public Mono<SplitResponse> split(String bucket, String s3Key, String documentId) {
        log.info("Split: downloading from bucket={}, key={}", bucket, s3Key);

        return s3Service.download(bucket, s3Key)
                .flatMap(pdfBytes -> {
                    try (ByteArrayInputStream inputStream = new ByteArrayInputStream(pdfBytes)) {

                        // Parse PDF (single Document)
                        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
                        Document doc = parser.parse(inputStream);

                        // Wrap into a list
                        List<Document> docs = List.of(doc);

                        // Split into segments
                        DocumentSplitter splitter = new DocumentByCharacterSplitter(1000, 50);
                        List<TextSegment> segments = splitter.splitAll(docs);


                        // Build response
                        List<PageChunks> pages = new ArrayList<>();
                        int chunkNo = 1;
                        int totalChunks = segments.size();

                        for (TextSegment segment : segments) {
                            pages.add(new PageChunks(chunkNo, totalChunks, List.of(segment.text())));
                            chunkNo++;
                        }

                        return Mono.just(new SplitResponse(documentId, pages));
                    } catch (IOException e) {
                        return Mono.error(e);
                    }
                });
    }
}
