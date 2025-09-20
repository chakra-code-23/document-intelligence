package com.document.intelligence.service;

import com.document.intelligence.dto.EntityInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EntityExtractionService {

    private final LangChainOllamaService llmService;
    private final ClaudeApiService claudeApiService;

    public Mono<List<EntityInfo>> extractEntities(String text) {
        try {
            String prompt = buildEntityExtractionPrompt(text);

            return claudeApiService.callClaudeReactive(prompt)
                    .map(response -> {
                        log.info("LLM Entity Response for text '{}': '{}'",
                                text.substring(0, Math.min(50, text.length())), response);

                        List<EntityInfo> entities = parseEntityResponse(response);

                        log.info("Extracted {} valid entities from text length: {}", entities.size(), text.length());

                        if (!entities.isEmpty()) {
                            log.info("Successfully extracted entities:");
                            entities.forEach(entity ->
                                    log.info("  - {} ({})", entity.getName(), entity.getType()));
                        } else {
                            log.warn("No valid entities extracted from LLM response");
                        }

                        return entities;
                    })
                    .onErrorResume(e -> {
                        log.error("Failed to extract entities from text: {}", e.getMessage(), e);
                        return Mono.just(Collections.emptyList());
                    });

        } catch (Exception e) {
            log.error("Failed to build entity extraction prompt: {}", e.getMessage(), e);
            return Mono.just(Collections.emptyList());
        }
    }


    private String buildEntityExtractionPrompt(String text) {
        return """
        TASK: Extract named entities ACTUALLY PRESENT in the text below. 
        Do NOT hallucinate or invent entities.

        VALID TYPES: PERSON, ORGANIZATION, LOCATION, DYNASTY, STRUCTURE, EVENT, ROLE, PRODUCT, CONCEPT, DATE, NUMBER, SKILL

        STRICT INSTRUCTIONS:
        - Only extract entities explicitly mentioned in the text
        - Return format: ENTITY_NAME|ENTITY_TYPE
        - One entity per line
        - If none exist, return "NONE"

        TEXT TO ANALYZE:
        "%s"

        ENTITIES FOUND IN THE TEXT:
        """.formatted(text.trim());
    }


    private List<EntityInfo> parseEntityResponse(String response) {
        List<EntityInfo> entities = new ArrayList<>();

        if (response == null || response.trim().isEmpty()) {
            log.warn("Empty or null response from LLM for entity extraction");
            return entities;
        }

        log.info("Raw LLM response for entity extraction: {}", response);

        // Handle case where LLM returns "NONE"
        if (response.trim().equalsIgnoreCase("NONE")) {
            log.info("LLM returned NONE - no entities found");
            return entities;
        }

        String[] lines = response.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip empty lines, explanatory text, and obvious non-entity lines
            if (line.toLowerCase().startsWith("entities") ||
                    line.toLowerCase().startsWith("here are") ||
                    line.toLowerCase().startsWith("person abilities") ||
                    line.toLowerCase().startsWith("however") ||
                    line.toLowerCase().startsWith("therefore") ||
                    line.toLowerCase().startsWith("to proceed") ||
                    line.toLowerCase().startsWith("if we were") ||
                    line.startsWith("-") ||
                    line.startsWith("*") ||
                    line.length() > 150 ||  // Skip very long lines (likely explanations)
                    !line.contains("|")) {   // Must contain pipe
                continue;
            }

            try {
                // Handle different formats LLM might use
                String[] parts;
                if (line.contains("||")) {
                    // Handle double pipe format
                    parts = line.split("\\|\\|", 2);
                } else if (line.contains("|")) {
                    parts = line.split("\\|", 2);
                } else {
                    continue; // Skip lines without pipes
                }

                if (parts != null && parts.length == 2) {
                    String name = cleanEntityName(parts[0].trim());
                    String type = normalizeEntityType(parts[1].trim());

                    log.info("Raw parsed - name: '{}', type: '{}'", name, type);

                    // Validate entity name and type
                    if (isValidEntityName(name) && isValidEntityType(type)) {
                        EntityInfo entity = new EntityInfo(name, type, 1.0);
                        entities.add(entity);
                        log.info("✅ Created and added entity: {} ({})", entity.getName(), entity.getType());
                    } else {
                        log.warn("❌ Filtered out invalid entity: name='{}' (valid={}), type='{}' (valid={})",
                                name, isValidEntityName(name), type, isValidEntityType(type));
                    }
                } else {
                    log.warn("❌ Could not parse line {}: '{}'", i + 1, line);
                }
            } catch (Exception e) {
                log.debug("Error parsing line {}: '{}' - {}", i + 1, line, e.getMessage());
            }
        }

        return entities;
    }

    private String cleanEntityName(String name) {
        if (name == null) return null;

        // Remove common prefixes and suffixes
        name = name.replaceAll("^(Entity:|Name:|the |a |an )", "").trim();
        name = name.replaceAll("\\.$", "").trim(); // Remove trailing period

        return name;
    }

    private String normalizeEntityType(String type) {
        if (type == null) return null;

        type = type.trim();

        // Remove leading/trailing pipes from double-pipe parsing issues - MORE AGGRESSIVE
        while (type.startsWith("|")) {
            type = type.substring(1);
        }
        while (type.endsWith("|")) {
            type = type.substring(0, type.length() - 1);
        }

        type = type.toUpperCase().trim();

        // Remove annotations like "(IMPLIED)", "(INFERRED)", and complex notes
        type = type.replaceAll("\\s*\\([^)]*\\).*$", "").trim();

        // Remove everything after "Note:" or similar patterns
        type = type.replaceAll("\\s*(Note:|NOTE:|\\(Note|\\(NOTE|Skip|SKIP|for context|FOR CONTEXT).*$", "").trim();

        // Handle common variations and synonyms - EXPANDED MAPPING
        Map<String, String> typeMap = Map.ofEntries(
                // Person variations
                Map.entry("PEOPLE", "PERSON"),
                Map.entry("INDIVIDUAL", "PERSON"),
                Map.entry("HUMAN", "PERSON"),

                // Organization variations
                Map.entry("COMPANY", "ORGANIZATION"),
                Map.entry("ORG", "ORGANIZATION"),
                Map.entry("ORGANISATION", "ORGANIZATION"),
                Map.entry("BUSINESS", "ORGANIZATION"),
                Map.entry("CORP", "ORGANIZATION"),
                Map.entry("CORPORATION", "ORGANIZATION"),
                Map.entry("EDUCATIONAL_INSTITUTION", "ORGANIZATION"),
                Map.entry("UNIVERSITY", "ORGANIZATION"),
                Map.entry("SCHOOL", "ORGANIZATION"),

                // Location variations
                Map.entry("PLACE", "LOCATION"),
                Map.entry("CITY", "LOCATION"),
                Map.entry("COUNTRY", "LOCATION"),
                Map.entry("REGION", "LOCATION"),
                Map.entry("AREA", "LOCATION"),

                // Product variations
                Map.entry("TECH", "PRODUCT"),
                Map.entry("TECHNOLOGY", "PRODUCT"),
                Map.entry("SOFTWARE", "PRODUCT"),
                Map.entry("TOOL", "PRODUCT"),
                Map.entry("PLATFORM", "PRODUCT"),
                Map.entry("SYSTEM", "PRODUCT"),
                Map.entry("APPLICATION", "PRODUCT"),
                Map.entry("APP", "PRODUCT"),
                Map.entry("FRAMEWORK", "PRODUCT"),
                Map.entry("LIBRARY", "PRODUCT"),
                Map.entry("FOOD_ITEM", "PRODUCT"),
                Map.entry("FOOD", "PRODUCT"),
                Map.entry("PROJECT_TYPE", "PRODUCT"),
                Map.entry("SERVICE", "PRODUCT"),

                // Concept variations
                Map.entry("SKILL", "CONCEPT"),
                Map.entry("IDEA", "CONCEPT"),
                Map.entry("METHOD", "CONCEPT"),
                Map.entry("TECHNIQUE", "CONCEPT"),
                Map.entry("APPROACH", "CONCEPT"),
                Map.entry("STRATEGY", "CONCEPT"),
                Map.entry("PROCESS", "CONCEPT"),
                Map.entry("ACTIVITY", "CONCEPT"),
                Map.entry("ACTION", "CONCEPT"),
                Map.entry("PRACTICE", "CONCEPT"),
                Map.entry("METHODOLOGY", "CONCEPT"),

                // Date variations
                Map.entry("TIME", "DATE"),
                Map.entry("PERIOD", "DATE"),
                Map.entry("DURATION", "DATE"),

                // Number variations
                Map.entry("NUM", "NUMBER"),
                Map.entry("QUANTITY", "NUMBER"),
                Map.entry("AMOUNT", "NUMBER"),
                Map.entry("COUNT", "NUMBER"),
                Map.entry("VALUE", "NUMBER")
        );


        return typeMap.getOrDefault(type, type);
    }

    private boolean isValidEntityName(String name) {
        if (name == null || name.trim().isEmpty()) {
            log.debug("Entity name is null or empty");
            return false;
        }

        name = name.trim();

        if (name.length() < 2) {
            log.debug("Entity name too short: '{}'", name);
            return false;
        }

        if (name.length() > 100) {
            log.debug("Entity name too long: '{}'", name);
            return false;
        }

        if (name.contains("\n") || name.contains("\r")) {
            log.debug("Entity name contains newlines: '{}'", name);
            return false;
        }

        // Filter out common non-entity words
        Set<String> stopWords = Set.of("THE", "A", "AN", "AND", "OR", "BUT", "IN", "ON", "AT", "TO", "FOR", "OF", "WITH", "BY");
        if (stopWords.contains(name.toUpperCase())) {
            log.debug("Entity name is a stop word: '{}'", name);
            return false;
        }

        return true;
    }

    private boolean isValidEntityType(String type) {
        if (type == null || type.trim().isEmpty()) {
            log.debug("Entity type is null or empty");
            return false;
        }

        Set<String> validTypes = Set.of(
                "PERSON", "ORGANIZATION", "LOCATION", "DYNASTY", "STRUCTURE",
                "EVENT", "ROLE", "PRODUCT", "CONCEPT", "DATE", "NUMBER", "SKILL"
        );

        boolean isValid = validTypes.contains(type.trim().toUpperCase());

        if (!isValid) {
            log.debug("Invalid entity type: '{}'. Valid types: {}", type, validTypes);
        }

        return isValid;
    }

}
