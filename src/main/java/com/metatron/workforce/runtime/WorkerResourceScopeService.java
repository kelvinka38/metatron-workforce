package com.metatron.workforce.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable per-Worker resource scope: which repositories a Worker may materialize and which repository
 * paths it may write or carry into a proposal. It narrows, never widens, what the runtime tool profile
 * already allows. A Worker with no declared scope keeps the pre-existing behaviour (no extra limits),
 * so existing Workers are unaffected.
 */
public final class WorkerResourceScopeService {

    public record Scope(String workerId, Set<String> repositories, List<String> writePathPrefixes, Instant declaredAt) {
        public Scope {
            require(workerId, "workerId");
            repositories = normalizedRepositories(repositories);
            writePathPrefixes = normalizedPrefixes(writePathPrefixes);
            Objects.requireNonNull(declaredAt, "declaredAt");
            if (repositories.isEmpty()) throw new IllegalArgumentException("resource scope requires at least one repository");
            if (writePathPrefixes.isEmpty()) throw new IllegalArgumentException("resource scope requires at least one write prefix");
        }

        boolean sameLimits(Scope other) {
            return repositories.equals(other.repositories) && writePathPrefixes.equals(other.writePathPrefixes);
        }
    }

    private final Path persistencePath;
    private final Map<String, Scope> scopes = new LinkedHashMap<>();

    public WorkerResourceScopeService(Path persistencePath) {
        this.persistencePath = persistencePath;
        load();
    }

    public static WorkerResourceScopeService inMemory() {
        return new WorkerResourceScopeService(null);
    }

    /** Idempotent: re-declaring identical limits returns the existing scope; different limits are a conflict. */
    public synchronized Scope declare(String workerId, Collection<String> repositories,
                                      Collection<String> writePathPrefixes, Instant at) {
        Scope requested = new Scope(workerId, Set.copyOf(repositories), List.copyOf(writePathPrefixes), at);
        Scope existing = scopes.get(workerId);
        if (existing != null) {
            if (!existing.sameLimits(requested)) {
                throw new IllegalStateException("worker resource scope conflict: " + workerId);
            }
            return existing;
        }
        scopes.put(workerId, requested);
        persist();
        return requested;
    }

    public synchronized Optional<Scope> find(String workerId) {
        return Optional.ofNullable(scopes.get(workerId));
    }

    public synchronized List<Scope> all() {
        return List.copyOf(scopes.values());
    }

    public void requireRepository(String workerId, String repository) {
        Scope scope = find(workerId).orElse(null);
        if (scope == null) return;
        String normalized = normalizeRepository(repository);
        if (!scope.repositories().contains(normalized)) {
            throw new SecurityException("worker resource scope denies repository " + repository
                    + " for " + workerId + "; allowed=" + scope.repositories());
        }
    }

    public void requireWritablePath(String workerId, String path) {
        Scope scope = find(workerId).orElse(null);
        if (scope == null) return;
        String normalized = safeRelativePath(path);
        if (scope.writePathPrefixes().stream().noneMatch(normalized::startsWith)) {
            throw new SecurityException("worker resource scope denies write path " + path
                    + " for " + workerId + "; allowed prefixes=" + scope.writePathPrefixes());
        }
    }

    /** Every changed path of a proposal must be writable under the scope; checked before any publish effect. */
    public void requireProposalPaths(String workerId, Collection<String> changedPaths) {
        Scope scope = find(workerId).orElse(null);
        if (scope == null) return;
        Objects.requireNonNull(changedPaths, "changedPaths");
        List<String> denied = new ArrayList<>();
        for (String path : changedPaths) {
            try {
                requireWritablePath(workerId, path);
            } catch (SecurityException | IllegalArgumentException outside) {
                denied.add(path);
            }
        }
        if (!denied.isEmpty()) {
            throw new SecurityException("worker resource scope denies proposal paths " + denied
                    + " for " + workerId + "; allowed prefixes=" + scope.writePathPrefixes());
        }
    }

    static String normalizeRepository(String repository) {
        String value = require(repository, "repository").trim();
        if (value.startsWith("repository:")) value = value.substring("repository:".length());
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        int at = value.indexOf('@');
        if (at > 0) value = value.substring(0, at);
        return value.toLowerCase(Locale.ROOT);
    }

    static String safeRelativePath(String path) {
        String value = require(path, "path").trim();
        if (value.startsWith("/") || value.contains("\\") || value.contains("\u0000")) {
            throw new SecurityException("unsafe workspace path: " + path);
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new SecurityException("unsafe workspace path: " + path);
            }
        }
        return value;
    }

    private static Set<String> normalizedRepositories(Set<String> repositories) {
        Objects.requireNonNull(repositories, "repositories");
        Set<String> out = new java.util.TreeSet<>();
        for (String repository : repositories) out.add(normalizeRepository(repository));
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(out));
    }

    private static List<String> normalizedPrefixes(List<String> prefixes) {
        Objects.requireNonNull(prefixes, "writePathPrefixes");
        List<String> out = new ArrayList<>();
        for (String prefix : prefixes) {
            String value = require(prefix, "writePathPrefix").trim();
            if (!value.endsWith("/")) throw new IllegalArgumentException("write path prefix must end with '/': " + prefix);
            safeRelativePath(value.substring(0, value.length() - 1));
            if (!out.contains(value)) out.add(value);
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    private void load() {
        if (persistencePath == null || !Files.exists(persistencePath)) return;
        try {
            for (String line : Files.readAllLines(persistencePath)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 4) throw new IllegalStateException("invalid worker resource scope state");
                Scope scope = new Scope(parts[0], Set.of(parts[1].split(",")), List.of(parts[2].split(",")),
                        Instant.parse(parts[3]));
                scopes.put(scope.workerId(), scope);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load worker resource scopes: " + persistencePath, e);
        }
    }

    private void persist() {
        if (persistencePath == null) return;
        try {
            Path parent = persistencePath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = persistencePath.resolveSibling(persistencePath.getFileName() + ".tmp");
            StringBuilder body = new StringBuilder("# workerId\trepositories\twritePathPrefixes\tdeclaredAt\n");
            scopes.values().stream().sorted(Comparator.comparing(Scope::workerId)).forEach(scope ->
                    body.append(scope.workerId()).append('\t')
                            .append(String.join(",", scope.repositories())).append('\t')
                            .append(String.join(",", scope.writePathPrefixes())).append('\t')
                            .append(scope.declaredAt()).append('\n'));
            Files.writeString(tmp, body.toString());
            try {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist worker resource scopes: " + persistencePath, e);
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value;
    }
}
