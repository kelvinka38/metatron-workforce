package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** JSON-backed atomic store for management Objective continuity across process/runtime replacement. */
public final class FileManagementStateStore implements ManagementStateStore {
    private final Path path;
    private final ObjectMapper mapper;

    public FileManagementStateStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public synchronized Snapshot load() {
        if (!Files.exists(path)) return Snapshot.empty();
        try {
            return mapper.readValue(path.toFile(), Snapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load management state: " + path, e);
        }
    }

    @Override
    public synchronized void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist management state: " + path, e);
        }
    }
}
