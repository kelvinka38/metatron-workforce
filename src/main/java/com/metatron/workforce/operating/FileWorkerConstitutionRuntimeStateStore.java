package com.metatron.workforce.operating;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Atomic JSON persistence for materialized Worker Constitution runtime snapshots. */
public final class FileWorkerConstitutionRuntimeStateStore implements WorkerConstitutionRuntimeStateStore {
    private final Path path;
    private final ObjectMapper json = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public FileWorkerConstitutionRuntimeStateStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public synchronized Snapshot load() {
        if (!Files.exists(path)) return Snapshot.empty();
        try {
            Snapshot value = json.readValue(path.toFile(), Snapshot.class);
            return value == null ? Snapshot.empty() : value;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load worker constitution runtime state: " + path, failure);
        }
    }

    @Override
    public synchronized void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), snapshot);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist worker constitution runtime state: " + path, failure);
        }
    }
}
