package com.metatron.workforce.deliberation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Single-node durable state store for Worker deliberation. */
public final class FileWorkerDeliberationStateStore implements WorkerDeliberationStateStore {
    private static final TypeReference<Map<String, WorkerDeliberationState>> MAP_TYPE = new TypeReference<>() {};
    private final Path path;
    private final ObjectMapper json;

    public FileWorkerDeliberationStateStore(Path path, ObjectMapper objectMapper) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().findAndRegisterModules();
    }

    @Override
    public synchronized Map<String, WorkerDeliberationState> load() {
        if (!Files.exists(path)) return Map.of();
        try {
            Map<String, WorkerDeliberationState> loaded = json.readValue(
                    Files.readString(path, StandardCharsets.UTF_8), MAP_TYPE);
            return loaded == null ? Map.of() : Map.copyOf(loaded);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load worker deliberation state: " + path, failure);
        }
    }

    @Override
    public synchronized void save(Map<String, WorkerDeliberationState> states) {
        Objects.requireNonNull(states, "states");
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".worker-deliberation-", ".tmp");
            Files.writeString(temp,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(new LinkedHashMap<>(states)),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist worker deliberation state: " + path, failure);
        }
    }
}
