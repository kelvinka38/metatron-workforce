package com.metatron.workforce.runtime.execution;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

public final class FileExecutionWorkspaceBindingStore implements ExecutionWorkspaceBindingStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FileExecutionWorkspaceBindingStore(Path path) { this.path = Objects.requireNonNull(path); }

    @Override public synchronized Map<String, ExecutionWorkspaceBinding> load() {
        if (!Files.exists(path)) return Map.of();
        try { return mapper.readValue(path.toFile(), new TypeReference<Map<String, ExecutionWorkspaceBinding>>() {}); }
        catch (Exception e) { throw new IllegalStateException("cannot load execution workspace bindings: " + path, e); }
    }

    @Override public synchronized void save(Map<String, ExecutionWorkspaceBinding> bindings) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), bindings);
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("cannot persist execution workspace bindings: " + path, e); }
    }
}
