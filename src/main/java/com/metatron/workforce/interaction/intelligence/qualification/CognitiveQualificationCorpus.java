package com.metatron.workforce.interaction.intelligence.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Frozen qualification corpus used to select a Metatron-owned Worker cognition model. */
public record CognitiveQualificationCorpus(
        String version,
        String sha256,
        List<CognitiveQualificationCase> cases) {

    public static final String VERSION = "METATRON_COGNITIVE_QUALIFICATION_V1";
    public static final String RESOURCE = "/cognition/METATRON_COGNITIVE_QUALIFICATION_V1.json";

    public CognitiveQualificationCorpus {
        version = require(version, "version");
        sha256 = require(sha256, "sha256");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (!VERSION.equals(version)) throw new IllegalArgumentException("unsupported qualification corpus version: " + version);
        if (cases.isEmpty()) throw new IllegalArgumentException("qualification corpus must not be empty");
        Set<String> ids = new HashSet<>();
        Map<CognitiveQualificationCategory, Integer> counts = new EnumMap<>(CognitiveQualificationCategory.class);
        for (CognitiveQualificationCase item : cases) {
            if (!ids.add(item.caseId())) throw new IllegalArgumentException("duplicate qualification case: " + item.caseId());
            counts.merge(item.category(), 1, Integer::sum);
        }
        for (CognitiveQualificationCategory category : CognitiveQualificationCategory.values()) {
            if (counts.getOrDefault(category, 0) < 2) {
                throw new IllegalArgumentException("qualification category requires at least two cases: " + category);
            }
        }
    }

    public static CognitiveQualificationCorpus load(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        try (InputStream input = CognitiveQualificationCorpus.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("qualification_corpus_resource_missing:" + RESOURCE);
            byte[] bytes = input.readAllBytes();
            Payload payload = mapper.readValue(bytes, Payload.class);
            return new CognitiveQualificationCorpus(payload.version(), sha256(bytes), payload.cases());
        } catch (IOException failure) {
            throw new IllegalStateException("qualification_corpus_load_failed", failure);
        }
    }

    public Map<CognitiveQualificationCategory, Long> categoryCounts() {
        Map<CognitiveQualificationCategory, Long> counts = new EnumMap<>(CognitiveQualificationCategory.class);
        for (CognitiveQualificationCategory category : CognitiveQualificationCategory.values()) {
            counts.put(category, cases.stream().filter(item -> item.category() == category).count());
        }
        return Map.copyOf(counts);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("qualification_corpus_hash_failed", impossible);
        }
    }

    private static String require(String value, String field) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return cleaned;
    }

    private record Payload(String version, List<CognitiveQualificationCase> cases) {}
}
