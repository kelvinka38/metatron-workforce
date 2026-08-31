package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Single-node durable safety ledger for the current production topology. */
public final class FileAutonomySafetyStateStore implements AutonomySafetyStateStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FileAutonomySafetyStateStore(Path path) { this.path = Objects.requireNonNull(path, "path"); }

    @Override public synchronized Snapshot load() {
        if (!Files.exists(path)) return Snapshot.empty();
        try { return mapper.readValue(path.toFile(), Snapshot.class); }
        catch (IOException failure) { throw new IllegalStateException("cannot load autonomy safety state: " + path, failure); }
    }

    @Override public synchronized void save(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot persist autonomy safety state: " + path, failure);
        }
    }
}
