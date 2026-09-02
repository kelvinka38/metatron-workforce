package com.metatron.workforce.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new SecurityException("objective workspace root cannot be a symlink");
            Path path = root.resolve(key).normalize();
            if (!path.startsWith(root)) throw new SecurityException("objective workspace escaped root");
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new SecurityException("objective workspace cannot be a symlink");
            }
            Files.createDirectories(path);
            Path identity = safeResolve(path, ".metatron-workspace");
            if (!Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
                atomicWrite(path, identity, "objective=" + objectiveId + "\nworker=" + workerId + "\n");
            } else if (Files.isSymbolicLink(identity)) {
                throw new SecurityException("workspace identity cannot be a symlink");
            }
            return new ObjectiveWorkspace("objective-workspace:" + key, key, objectiveId, workerId, path, Instant.now());
        } catch (IOException e) {
            throw new IllegalStateException("cannot provision objective workspace", e);
        }
    }

    public String read(ObjectiveWorkspace workspace, String relativePath) {
        Path path = resolve(workspace, relativePath);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace file not found: " + relativePath);
            }
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
        if (path.equals(workspace.path())) throw new SecurityException("workspace root cannot be replaced");
        try {
            ensureParentDirectories(workspace.path(), path.getParent());
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new SecurityException("workspace write through symlink denied");
            }
            atomicWrite(workspace.path(), path, content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write workspace file: " + relativePath, e);
        }
    }

    public List<String> list(ObjectiveWorkspace workspace, String relativePath) {
        Path start = relativePath == null || relativePath.isBlank() ? workspace.path() : resolve(workspace, relativePath);
        try (var stream = Files.walk(start, 4)) {
            return stream.filter(path -> !path.equals(workspace.path()))
                    .filter(path -> !Files.isSymbolicLink(path))
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
        Path workspaceRoot = workspace.path().toAbsolutePath().normalize();
        if (relativePath == null || relativePath.isBlank()) return workspaceRoot;
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute()) throw new SecurityException("absolute workspace path denied");
        Path resolved = workspaceRoot.resolve(requested).normalize();
        if (!resolved.startsWith(workspaceRoot)) throw new SecurityException("workspace path escape denied");
        rejectExistingSymlinks(workspaceRoot, resolved);
        return resolved;
    }

    private static Path safeResolve(Path workspaceRoot, String relativePath) {
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot)) throw new SecurityException("workspace path escape denied");
        rejectExistingSymlinks(workspaceRoot, resolved);
        return resolved;
    }

    private static void rejectExistingSymlinks(Path workspaceRoot, Path resolved) {
        Path current = workspaceRoot;
        Path relative = workspaceRoot.relativize(resolved);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new SecurityException("workspace symlink traversal denied: " + relative);
            }
        }
    }

    private static void ensureParentDirectories(Path workspaceRoot, Path parent) throws IOException {
        if (parent == null) return;
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        if (!normalizedParent.startsWith(normalizedRoot)) throw new SecurityException("workspace parent escape denied");
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedParent)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) throw new SecurityException("workspace parent symlink denied");
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("workspace parent is not a directory: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static void atomicWrite(Path workspaceRoot, Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !parent.toAbsolutePath().normalize().startsWith(workspaceRoot.toAbsolutePath().normalize())) {
            throw new SecurityException("workspace write parent escaped root");
        }
        Path tmp = Files.createTempFile(parent, ".metatron-write-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
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
