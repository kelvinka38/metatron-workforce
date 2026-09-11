package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Single-node durable routing-feedback store for current production topology. */
public final class FileIntelligenceRoutingFeedbackStore implements IntelligenceRoutingFeedbackStore {
    private final Path path;
    private final ObjectMapper json;

    public FileIntelligenceRoutingFeedbackStore(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
    }

    @Override
    public synchronized Snapshot load() {
        if (!Files.exists(path)) return Snapshot.empty();
        try {
            return json.readValue(Files.readString(path, StandardCharsets.UTF_8), Snapshot.class);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load intelligence routing feedback: " + path, failure);
        }
    }

    @Override
    public synchronized void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".routing-feedback-", ".tmp");
            Files.writeString(temp, json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist intelligence routing feedback: " + path, failure);
        }
    }
}
