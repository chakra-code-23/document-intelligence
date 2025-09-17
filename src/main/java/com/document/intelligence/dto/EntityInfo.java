package com.document.intelligence.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EntityInfo {
    private String name;
    private String type;
    private double confidence;

    public EntityInfo(String name, String type) {
        this.name = name;
        this.type = type;
        this.confidence = 1.0;
    }
}