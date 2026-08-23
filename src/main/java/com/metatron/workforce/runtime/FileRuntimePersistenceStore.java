package com.metatron.workforce.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Single-node durable runtime store backed by atomic filesystem snapshots.
 *
 * This is the reference production-substrate implementation for the current
 * deployable runtime. A multi-node deployment may replace this store without
 * changing Workforce/Execution semantics.
 */
public final class FileRuntimePersistenceStore implements RuntimePersistenceStore {
    private final Path root;

    public FileRuntimePersistenceStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void save(RuntimePersistenceRecord record) {
        try {
            Files.createDirectories(root);
            Properties properties = new Properties();
            properties.setProperty("runtimeId", record.runtimeId());
            properties.setProperty("workerId", record.workerId());
            properties.setProperty("state", record.state());
            properties.setProperty("updatedAt", record.updatedAt().toString());

            Path target = path(record.runtimeId());
            Path temporary = Files.createTempFile(root, record.runtimeId() + ".", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Metatron Workforce runtime persistence");
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("unable to persist runtime " + record.runtimeId(), e);
        }
    }

    @Override
    public synchronized Optional<RuntimePersistenceRecord> find(String runtimeId) {
        Path path = path(runtimeId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try (InputStream input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            return Optional.of(new RuntimePersistenceRecord(
                    properties.getProperty("runtimeId"),
                    properties.getProperty("workerId"),
                    properties.getProperty("state"),
                    java.time.Instant.parse(properties.getProperty("updatedAt"))));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("unable to load runtime " + runtimeId, e);
        }
    }

    @Override
    public synchronized void delete(String runtimeId) {
        try {
            Files.deleteIfExists(path(runtimeId));
        } catch (IOException e) {
            throw new IllegalStateException("unable to delete runtime " + runtimeId, e);
        }
    }

    private Path path(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank() || runtimeId.contains("..")
                || runtimeId.contains("/") || runtimeId.contains("\\")) {
            throw new IllegalArgumentException("invalid runtime id");
        }
        return root.resolve(runtimeId + ".properties");
    }
}
