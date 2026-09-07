package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Durable append-only journal for Cognitive Worker action observations and reflections. */
@FunctionalInterface
public interface ActionJournal {
    void append(String objectiveId,
                String workStepId,
                String workerId,
                String assignmentReference,
                String authorizationReference,
                String idempotencyKey,
                CognitiveWorkerRuntime.Cycle cycle);

    /**
     * Rehydrates durable evidence from every action attempt for one Objective.
     * Replanning may replace WorkGraph attempts, but it must never erase observed history.
     */
    default List<String> objectiveEvidenceReferences(String objectiveId) { return List.of(); }

    /** Durable action-level execution truth for one Objective, newest first. */
    default List<ActionRecord> objectiveActionRecords(String objectiveId) { return List.of(); }

    record ActionRecord(
            Instant recordedAt,
            String objectiveId,
            String workStepId,
            String workerId,
            String assignmentReference,
            int cycle,
            String actionRef,
            String consequence,
            boolean success,
            String summary,
            Map<String, String> inputs,
            Map<String, String> outputs,
            List<String> evidenceReferences,
            String reflection,
            String reflectionSummary) {}

    static ActionJournal noop() { return (a, b, c, d, e, f, g) -> { }; }

    static ActionJournal runtimeEvidenceJournal() {
        String evidenceRoot = env("METATRON_RUNTIME_EVIDENCE_DIR", "runtime-evidence");
        String configured = env("METATRON_ACTION_JOURNAL_DIR", Path.of(evidenceRoot, "action-journal").toString());
        return new FileJournal(Path.of(configured));
    }

    final class FileJournal implements ActionJournal {
        private static final ObjectMapper JSON = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        private final Path root;

        public FileJournal(Path root) {
            this.root = root;
        }

        @Override
        public synchronized void append(String objectiveId, String workStepId, String workerId,
                                        String assignmentReference, String authorizationReference,
                                        String idempotencyKey, CognitiveWorkerRuntime.Cycle cycle) {
            try {
                Path directory = root.resolve(safe(objectiveId));
                Files.createDirectories(directory);
                Path file = directory.resolve(safe(workStepId) + ".jsonl");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recordedAt", Instant.now());
                row.put("objectiveId", objectiveId);
                row.put("workStepId", workStepId);
                row.put("workerId", workerId);
                row.put("assignmentReference", assignmentReference);
                row.put("authorizationReference", authorizationReference);
                row.put("idempotencyKey", idempotencyKey);
                row.put("cycle", cycle.number());
                row.put("thoughtAction", cycle.thought().actionRef());
                row.put("thoughtInputs", cycle.thought().inputs());
                row.put("thoughtRationale", cycle.thought().rationale());
                row.put("actionSuccess", cycle.observation().success());
                row.put("actionSummary", cycle.observation().summary());
                row.put("outputs", cycle.observation().outputs());
                row.put("evidenceReferences", cycle.observation().evidenceReferences());
                row.put("reflection", cycle.reflection().decision().name());
                row.put("reflectionSummary", cycle.reflection().summary());
                Files.writeString(file, JSON.writeValueAsString(row) + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("action-journal-persistence-failed", failure);
            }
        }

        @Override
        public synchronized List<ActionRecord> objectiveActionRecords(String objectiveId) {
            Path directory = root.resolve(safe(objectiveId));
            if (!Files.exists(directory)) return List.of();
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("action-journal-objective-path-is-not-directory");
            }
            List<ActionRecord> records = new ArrayList<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted().toList()) {
                    for (String line : Files.readAllLines(file)) {
                        if (line.isBlank()) continue;
                        JsonNode row = JSON.readTree(line);
                        if (!objectiveId.equals(row.path("objectiveId").asText())) {
                            throw new IllegalStateException("action-journal-objective-mismatch");
                        }
                        List<String> refs = new ArrayList<>();
                        JsonNode evidence = row.path("evidenceReferences");
                        if (evidence.isArray()) for (JsonNode ref : evidence) if (ref.isTextual()) refs.add(ref.asText());
                        String consequence = refs.stream()
                                .filter(ref -> ref.startsWith("action-fabric:"))
                                .map(ref -> field(ref, ":consequence="))
                                .filter(value -> !value.isBlank())
                                .findFirst().orElse("UNKNOWN");
                        records.add(new ActionRecord(
                                Instant.parse(row.path("recordedAt").asText()),
                                objectiveId,
                                row.path("workStepId").asText(),
                                row.path("workerId").asText(),
                                row.path("assignmentReference").asText(),
                                row.path("cycle").asInt(),
                                row.path("thoughtAction").asText(),
                                consequence,
                                row.path("actionSuccess").asBoolean(false),
                                row.path("actionSummary").asText(),
                                stringMap(row.path("thoughtInputs")),
                                stringMap(row.path("outputs")),
                                List.copyOf(refs),
                                row.path("reflection").asText(),
                                row.path("reflectionSummary").asText()));
                        if (records.size() >= 5_000) break;
                    }
                    if (records.size() >= 5_000) break;
                }
            } catch (IOException failure) {
                throw new IllegalStateException("action-journal-record-read-failed", failure);
            }
            records.sort(java.util.Comparator.comparing(ActionRecord::recordedAt).reversed());
            return List.copyOf(records);
        }

        private static Map<String, String> stringMap(JsonNode node) {
            if (!node.isObject()) return Map.of();
            Map<String, String> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(),
                    entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
            return Map.copyOf(values);
        }

        private static String field(String ref, String marker) {
            int start = ref.indexOf(marker);
            if (start < 0) return "";
            start += marker.length();
            int end = ref.indexOf(':', start);
            return (end < 0 ? ref.substring(start) : ref.substring(start, end)).trim();
        }

        @Override
        public synchronized List<String> objectiveEvidenceReferences(String objectiveId) {
            Path directory = root.resolve(safe(objectiveId));
            if (!Files.exists(directory)) return List.of();
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("action-journal-objective-path-is-not-directory");
            }
            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                        .sorted().toList()) {
                    for (String line : Files.readAllLines(file)) {
                        if (line.isBlank()) continue;
                        JsonNode row = JSON.readTree(line);
                        if (!objectiveId.equals(row.path("objectiveId").asText())) {
                            throw new IllegalStateException("action-journal-objective-mismatch");
                        }
                        JsonNode refs = row.path("evidenceReferences");
                        if (refs.isArray()) {
                            for (JsonNode ref : refs) {
                                if (ref.isTextual() && !ref.asText().isBlank()) evidence.add(ref.asText().trim());
                                if (evidence.size() >= 5_000) return List.copyOf(evidence);
                            }
                        }
                        String action = row.path("thoughtAction").asText("");
                        String success = row.path("actionSuccess").asText("");
                        String step = row.path("workStepId").asText("");
                        String cycle = row.path("cycle").asText("");
                        if (!action.isBlank()) {
                            evidence.add("action-journal:action=" + action
                                    + ":success=" + success
                                    + ":step=" + step
                                    + ":cycle=" + cycle);
                        }
                        if (evidence.size() >= 5_000) return List.copyOf(evidence);
                    }
                }
                return List.copyOf(evidence);
            } catch (IOException failure) {
                throw new IllegalStateException("action-journal-rehydration-failed", failure);
            }
        }

        private static String safe(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("journal identity required");
            return value.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
