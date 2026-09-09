package com.metatron.workforce.interaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable active Direct Worker binding keyed by canonical conversation identity. */
@Service
public final class DirectWorkerConversationBindingStore {
    private final ObjectMapper json;
    private final Path path;

    public DirectWorkerConversationBindingStore(
            ObjectMapper json,
            @Value("${METATRON_DIRECT_WORKER_BINDING_PATH:/var/lib/metatron-workforce/direct-worker-bindings.json}") String path) {
        this.json = Objects.requireNonNull(json, "json");
        this.path = Path.of(Objects.requireNonNull(path, "path"));
    }

    DirectWorkerConversationBindingStore(ObjectMapper json, Path path) {
        this.json = Objects.requireNonNull(json, "json");
        this.path = Objects.requireNonNull(path, "path");
    }

    public synchronized Optional<String> workerId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Optional.empty();
        String value = read().get(conversationId);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public synchronized void bind(String conversationId, String workerId) {
        if (conversationId == null || conversationId.isBlank()) throw new IllegalArgumentException("conversationId required");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        Map<String,String> values = read();
        values.put(conversationId, workerId);
        write(values);
    }

    public synchronized void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Map<String,String> values = read();
        if (values.remove(conversationId) != null) write(values);
    }

    private Map<String,String> read() {
        try {
            if (!Files.exists(path)) return new LinkedHashMap<>();
            Map<String,String> value = json.readValue(
                    Files.readString(path, StandardCharsets.UTF_8),
                    new TypeReference<Map<String,String>>() {});
            return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        } catch (Exception failure) {
            return new LinkedHashMap<>();
        }
    }

    private void write(Map<String,String> values) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path dir = parent == null ? Path.of(".") : parent;
            Path temp = Files.createTempFile(dir, "direct-worker-binding-", ".tmp");
            Files.writeString(temp, json.writeValueAsString(values), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("direct_worker_binding_write_failed", failure);
        }
    }
}
