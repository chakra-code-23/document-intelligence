package com.document.intelligence.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class CompletionResponse {

    private String documentId;
    private String topicId;
    private String summary;
    private List<String> questions;
}
