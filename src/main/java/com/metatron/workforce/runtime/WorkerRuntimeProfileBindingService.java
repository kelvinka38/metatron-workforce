package com.metatron.workforce.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Durable binding between a formed Worker and the runtime/tool profile it is actually allowed to use.
 * Formation policy references are no longer evidence-only strings: they resolve to an executable tool profile.
 */
public final class WorkerRuntimeProfileBindingService {
    public static final String GENERAL_ENGINEERING_PROFILE = "runtime-profile:general-engineering-worker:v1";

    public record ToolProfile(
            String profileRef,
            Set<String> actionRefs,
            Set<String> allowedExecutables,
            boolean writableWorkspace,
            int maxProcessSeconds,
            int maxOutputBytes) {
        public ToolProfile {
            require(profileRef, "profileRef");
            actionRefs = Set.copyOf(Objects.requireNonNull(actionRefs, "actionRefs"));
            allowedExecutables = Set.copyOf(Objects.requireNonNull(allowedExecutables, "allowedExecutables"));
            if (actionRefs.isEmpty()) throw new IllegalArgumentException("runtime tool profile requires at least one action");
            if (maxProcessSeconds < 1) throw new IllegalArgumentException("maxProcessSeconds must be positive");
            if (maxOutputBytes < 1024) throw new IllegalArgumentException("maxOutputBytes too small");
        }
    }

    public record Binding(String workerId, String capabilityRef, ToolProfile profile, Instant boundAt) {
        public Binding {
            require(workerId, "workerId");
            require(capabilityRef, "capabilityRef");
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(boundAt, "boundAt");
        }
    }

    private final Path persistencePath;
    private final Map<String, Binding> bindings = new LinkedHashMap<>();

    public WorkerRuntimeProfileBindingService(Path persistencePath) {
        this.persistencePath = persistencePath;
        load();
    }

    public static WorkerRuntimeProfileBindingService inMemory() {
        return new WorkerRuntimeProfileBindingService(null);
    }

    public synchronized Binding bind(String workerId, String runtimeProfileRef, String capabilityRef, Instant at) {
        require(workerId, "workerId");
        require(runtimeProfileRef, "runtimeProfileRef");
        require(capabilityRef, "capabilityRef");
        Objects.requireNonNull(at, "at");
        ToolProfile profile = profile(runtimeProfileRef, capabilityRef);
        Binding existing = bindings.get(workerId);
        if (existing != null) {
            if (!existing.profile().profileRef().equals(profile.profileRef())) {
                throw new IllegalStateException("worker runtime profile conflict: " + workerId);
            }
            if (!existing.capabilityRef().equals(capabilityRef)) {
                Binding widened = new Binding(workerId, capabilityRef, profile, existing.boundAt());
                bindings.put(workerId, widened);
                persist();
                return widened;
            }
            return existing;
        }
        Binding created = new Binding(workerId, capabilityRef, profile, at);
        bindings.put(workerId, created);
        persist();
        return created;
    }

    public synchronized Optional<Binding> find(String workerId) {
        return Optional.ofNullable(bindings.get(workerId));
    }

    public synchronized Binding requireBinding(String workerId) {
        return find(workerId).orElseThrow(() -> new IllegalStateException("worker-runtime-profile-unbound:" + workerId));
    }

    public synchronized List<Binding> all() {
        return List.copyOf(bindings.values());
    }

    public ToolProfile profile(String runtimeProfileRef, String capabilityRef) {
        require(runtimeProfileRef, "runtimeProfileRef");
        require(capabilityRef, "capabilityRef");
        if (GENERAL_ENGINEERING_PROFILE.equals(runtimeProfileRef)) {
            return new ToolProfile(
                    runtimeProfileRef,
                    Set.of(
                            "research.web.search",
                            "workspace.repository.materialize",
                            "workspace.file.read", "workspace.file.list", "workspace.file.search", "workspace.file.patch", "workspace.file.write",
                            "workspace.dependencies.install",
                            "workspace.process.run", "workspace.shell.run",
                            "workspace.git.status", "workspace.git.diff", "workspace.git.run",
                            "workspace.github.pr.publish",
                            "workspace.build.run", "workspace.test.run"),
                    Set.of(
                            "git", "java", "javac", "sh", "bash", "gradle", "mvn", "./gradlew", "./mvnw",
                            "node", "npm", "npx", "pnpm", "yarn",
                            "python3", "python", "pip3"),
                    true, 300, 2_000_000);
        }
        // Bounded legacy profiles remain usable through their already-authorized capability adapter.
        return new ToolProfile(runtimeProfileRef, Set.of("capability:" + capabilityRef), Set.of(), false, 60, 512_000);
    }

    private void load() {
        if (persistencePath == null || !Files.exists(persistencePath)) return;
        try {
            for (String line : Files.readAllLines(persistencePath)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 4) throw new IllegalStateException("invalid runtime profile binding state");
                String workerId = parts[0];
                String capabilityRef = parts[1];
                ToolProfile profile = profile(parts[2], capabilityRef);
                bindings.put(workerId, new Binding(workerId, capabilityRef, profile, Instant.parse(parts[3])));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load runtime profile bindings: " + persistencePath, e);
        }
    }

    private void persist() {
        if (persistencePath == null) return;
        try {
            Path parent = persistencePath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = persistencePath.resolveSibling(persistencePath.getFileName() + ".tmp");
            StringBuilder body = new StringBuilder("# workerId\\tcapabilityRef\\tprofileRef\\tboundAt\n");
            bindings.values().stream().sorted(java.util.Comparator.comparing(Binding::workerId)).forEach(binding ->
                    body.append(binding.workerId()).append('\t')
                            .append(binding.capabilityRef()).append('\t')
                            .append(binding.profile().profileRef()).append('\t')
                            .append(binding.boundAt()).append('\n'));
            Files.writeString(tmp, body.toString());
            try {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, persistencePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist runtime profile bindings: " + persistencePath, e);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
