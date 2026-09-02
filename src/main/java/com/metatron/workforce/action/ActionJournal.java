package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
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
