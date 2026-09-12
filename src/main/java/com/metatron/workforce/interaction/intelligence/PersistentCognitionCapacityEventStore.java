package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Append-only durable cognition-capacity event evidence with restart reconciliation. */
public final class PersistentCognitionCapacityEventStore implements CognitionCapacityEventStore {
    private static final Set<CognitionRequestState> INCOMPLETE = Set.of(
            CognitionRequestState.ADMITTED,
            CognitionRequestState.QUEUED,
            CognitionRequestState.RUNNING,
            CognitionRequestState.FAILED_RETRYABLE);

    private final Path path;
    private final ObjectMapper json;
    private final List<CognitionCapacityEvent> events = new ArrayList<>();

    public PersistentCognitionCapacityEventStore(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().registerModule(new JavaTimeModule());
        load();
    }

    @Override
    public synchronized void append(CognitionCapacityEvent event) {
        Objects.requireNonNull(event, "event");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            events.add(event);
        } catch (IOException failure) {
            throw new IllegalStateException("cognition_capacity_event_write_failed", failure);
        }
    }

    @Override
    public synchronized List<CognitionCapacityEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public synchronized int reconcileIncomplete() {
        Map<String, CognitionCapacityEvent> latest = new LinkedHashMap<>();
        for (CognitionCapacityEvent event : events) latest.put(event.requestId(), event);
        List<CognitionCapacityEvent> reconciliation = latest.values().stream()
                .filter(event -> INCOMPLETE.contains(event.state()))
                .map(CognitionCapacityEvent::reconciliationRequired)
                .toList();
        reconciliation.forEach(this::append);
        return reconciliation.size();
    }

    private void load() {
        if (!Files.exists(path)) return;
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    events.add(json.readValue(line, CognitionCapacityEvent.class));
                } catch (Exception malformed) {
                    throw new IllegalStateException("cognition_capacity_event_malformed_line:" + lineNumber, malformed);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cognition_capacity_event_read_failed", failure);
        }
    }
}
