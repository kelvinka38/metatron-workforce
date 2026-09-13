package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Durable append-only inference consumption ledger.
 *
 * <p>The ledger is evidence, never action authority. Each JSONL line is one immutable
 * {@link InferenceConsumptionRecord}. Restart replay is deterministic and a malformed line fails closed
 * rather than silently dropping economic/provenance evidence.</p>
 */
public final class PersistentInferenceConsumptionLedger implements InferenceConsumptionLedger {
    private final Path path;
    private final ObjectMapper json;
    private final List<InferenceConsumptionRecord> records = new ArrayList<>();

    public PersistentInferenceConsumptionLedger(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().registerModule(new JavaTimeModule());
        load();
    }

    @Override
    public synchronized void record(InferenceConsumptionRecord record) {
        Objects.requireNonNull(record, "record");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            String line = json.writeValueAsString(record) + "\n";
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            records.add(record);
        } catch (IOException failure) {
            throw new IllegalStateException("inference_ledger_write_failed", failure);
        }
    }

    @Override
    public synchronized List<InferenceConsumptionRecord> records() {
        return List.copyOf(records);
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    records.add(json.readValue(line, InferenceConsumptionRecord.class));
                } catch (Exception malformed) {
                    throw new IllegalStateException("inference_ledger_malformed_line:" + lineNumber, malformed);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("inference_ledger_read_failed", failure);
        }
    }
}
