package com.metatron.workforce.runtime.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class FileIntegrationQueueStore implements IntegrationQueueStore {
    private static final TypeReference<Map<String, IntegrationQueueEntry>> TYPE = new TypeReference<>() {};
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FileIntegrationQueueStore(Path path) { this.path = Objects.requireNonNull(path, "path"); }

    @Override public synchronized Map<String, IntegrationQueueEntry> load() {
        if (!Files.exists(path)) return Map.of();
        try { return Map.copyOf(new LinkedHashMap<>(mapper.readValue(path.toFile(), TYPE))); }
        catch (Exception e) { throw new IllegalStateException("cannot load integration queue: " + path, e); }
    }

    @Override public synchronized void save(Map<String, IntegrationQueueEntry> entries) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new LinkedHashMap<>(entries));
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("cannot persist integration queue: " + path, e); }
    }
}
