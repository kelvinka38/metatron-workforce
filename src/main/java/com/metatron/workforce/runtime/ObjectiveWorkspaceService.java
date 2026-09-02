package com.metatron.workforce.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Durable writable workspace owned by one Objective/Worker pair. All paths are containment-checked. */
public final class ObjectiveWorkspaceService {
    private static final int MAX_FILE_BYTES = 4_000_000;
    private final Path root;

    public record ObjectiveWorkspace(
            String workspaceRef,
            String workspaceKey,
            String objectiveId,
            String workerId,
            Path path,
            Instant provisionedAt) {
        public ObjectiveWorkspace {
            require(workspaceRef, "workspaceRef");
            require(workspaceKey, "workspaceKey");
            require(objectiveId, "objectiveId");
            require(workerId, "workerId");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(provisionedAt, "provisionedAt");
        }
    }

    public ObjectiveWorkspaceService(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public synchronized ObjectiveWorkspace provision(String objectiveId, String workerId) {
        require(objectiveId, "objectiveId");
        require(workerId, "workerId");
        String key = stableKey(objectiveId + "\n" + workerId);
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) throw new SecurityException("objective workspace escaped root");
        try {
            Files.createDirectories(path);
            Path identity = path.resolve(".metatron-workspace");
            if (!Files.exists(identity)) {
                Files.writeString(identity, "objective=" + objectiveId + "\nworker=" + workerId + "\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot provision objective workspace", e);
        }
        return new ObjectiveWorkspace("objective-workspace:" + key, key, objectiveId, workerId, path, Instant.now());
    }

    public String read(ObjectiveWorkspace workspace, String relativePath) {
        Path path = resolve(workspace, relativePath);
        try {
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("workspace file not found: " + relativePath);
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) throw new IllegalStateException("workspace file exceeds read limit: " + relativePath);
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read workspace file: " + relativePath, e);
        }
    }

    public void write(ObjectiveWorkspace workspace, String relativePath, String content) {
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("workspace write exceeds file limit");
        Path path = resolve(workspace, relativePath);
        try {
            if (path.equals(workspace.path())) throw new SecurityException("workspace root cannot be replaced");
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot write workspace file: " + relativePath, e);
        }
    }

    public List<String> list(ObjectiveWorkspace workspace, String relativePath) {
        Path start = relativePath == null || relativePath.isBlank() ? workspace.path() : resolve(workspace, relativePath);
        try (var stream = Files.walk(start, 4)) {
            return stream.filter(path -> !path.equals(workspace.path()))
                    .map(workspace.path()::relativize)
                    .map(Path::toString)
                    .sorted()
                    .limit(500)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list objective workspace", e);
        }
    }

    public Path resolve(ObjectiveWorkspace workspace, String relativePath) {
        Objects.requireNonNull(workspace, "workspace");
        if (relativePath == null || relativePath.isBlank()) return workspace.path();
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute()) throw new SecurityException("absolute workspace path denied");
        Path resolved = workspace.path().resolve(requested).normalize();
        if (!resolved.startsWith(workspace.path())) throw new SecurityException("workspace path escape denied");
        return resolved;
    }

    private static String stableKey(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
