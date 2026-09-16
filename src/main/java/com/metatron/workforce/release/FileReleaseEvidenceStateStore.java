package com.metatron.workforce.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Durable, atomic-write, restart-surviving persistence for release evidence -- mirrors
 * com.metatron.workforce.core.FileWorkforceCoreStateStore exactly (same ObjectMapper/JavaTimeModule
 * setup, same tmp-file-then-atomic-move write, same empty-on-missing-file load).
 */
public final class FileReleaseEvidenceStateStore implements ReleaseEvidenceStateStore {
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public FileReleaseEvidenceStateStore(Path path) { this.path = Objects.requireNonNull(path); }

    @Override public synchronized Snapshot load() {
        if (!Files.exists(path)) return Snapshot.empty();
        try { return mapper.readValue(path.toFile(), Snapshot.class); }
        catch (IOException e) { throw new IllegalStateException("cannot load release evidence state: " + path, e); }
    }

    @Override public synchronized void save(Snapshot snapshot) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("cannot persist release evidence state: " + path, e); }
    }
}
