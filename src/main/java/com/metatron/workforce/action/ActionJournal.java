package com.metatron.workforce.action;

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
     * Rehydrates durable action evidence accumulated across prior Worker attempts/replans for one Objective.
     * Implementations must return only evidence that was actually journaled by governed Action Fabric cycles.
     */
    default List<String> objectiveEvidenceReferences(String objectiveId) { return List.of(); }

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
        public synchronized List<String> objectiveEvidenceReferences(String objectiveId) {
            Path directory = root.resolve(safe(objectiveId));
            if (!Files.isDirectory(directory)) return List.of();

            LinkedHashSet<String> evidence = new LinkedHashSet<>();
            try (var files = Files.list(directory)) {
                for (Path file : files
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .toList()) {
                    for (String line : Files.readAllLines(file)) {
                        if (line == null || line.isBlank()) continue;
                        Map<?, ?> row;
                        try {
                            row = JSON.readValue(line, Map.class);
                        } catch (Exception invalid) {
                            throw new IllegalStateException("action-journal-read-invalid-json:" + file.getFileName(), invalid);
                        }
                        Object refs = row.get("evidenceReferences");
                        if (refs instanceof List<?> list) {
                            for (Object ref : list) {
                                if (ref != null && !String.valueOf(ref).isBlank()) evidence.add(String.valueOf(ref));
                            }
                        }
                        String action = String.valueOf(row.getOrDefault("thoughtAction", ""));
                        String step = String.valueOf(row.getOrDefault("workStepId", ""));
                        String cycle = String.valueOf(row.getOrDefault("cycle", ""));
                        String success = String.valueOf(row.getOrDefault("actionSuccess", ""));
                        if (!action.isBlank()) {
                            evidence.add("action-journal:action=" + action
                                    + ":success=" + success
                                    + ":step=" + step
                                    + ":cycle=" + cycle);
                        }
                        if (evidence.size() >= 5_000) return List.copyOf(evidence);
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("action-journal-read-failed", failure);
            }
            return List.copyOf(evidence);
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
