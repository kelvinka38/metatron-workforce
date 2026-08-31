package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Founder-approved, read-only P10 recovery probe.
 *
 * The probe exists only to inject bounded production failures. It never mutates an external system.
 * Management/Execution/Runtime must recover the Objective through their canonical state machines.
 */
@Component
public final class AutonomyRecoveryProbeCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "autonomy.recovery.probe.read";
    public static final String WORKER_ID = "WORKER-AUTONOMY-RECOVERY-PROBE";
    public static final String AUTHORITY_REFERENCE = "policy:founder-autonomy-p10-recovery-probe:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-autonomy-p10-recovery-probe:v1";
    public static final String TARGET_PREFIX = "p10-recovery://";
    public static final String DEFAULT_ROOT = "/var/lib/metatron-workforce/recovery-probes";

    private final ObjectMapper json;
    private final Path root;
    private final Duration restartWindow;

    @Autowired
    public AutonomyRecoveryProbeCapability(ObjectMapper json) {
        this(json,
                Path.of(System.getenv().getOrDefault("METATRON_RECOVERY_PROBE_DIR", DEFAULT_ROOT)),
                Duration.ofSeconds(120));
    }

    AutonomyRecoveryProbeCapability(ObjectMapper json, Path root, Duration restartWindow) {
        this.json = Objects.requireNonNull(json, "json");
        this.root = Objects.requireNonNull(root, "root");
        this.restartWindow = Objects.requireNonNull(restartWindow, "restartWindow");
        if (restartWindow.isNegative() || restartWindow.isZero()) {
            throw new IllegalArgumentException("restartWindow must be positive");
        }
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — P10 read-only failure injection for transient timeout, runtime restart, and Observation retry; no external mutation";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        Mode mode = mode(request.workSpec().target());

        if (mode == Mode.TRANSIENT_TIMEOUT && request.dispatchAttempt() == 1) {
            throw new IllegalStateException("provider-timeout-injected:p10-recovery-probe");
        }
        if (mode == Mode.RESTART_WINDOW && request.dispatchAttempt() == 1) {
            try {
                Thread.sleep(restartWindow.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("runtime-terminated-during-restart-window", interrupted);
            }
            throw new IllegalStateException("restart-window-expired-without-runtime-termination");
        }

        Instant completedAt = Instant.now();
        Path marker = markerPath(root, request.objectiveId(), request.workSpec().target());
        persistMarker(marker, request, mode, completedAt);

        return new CapabilityResult(
                true,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                "work:p10-recovery-probe:" + marker.getFileName(),
                List.of(
                        "p10-recovery-marker:" + marker.getFileName(),
                        "p10-recovery-mode:" + mode.name().toLowerCase(Locale.ROOT),
                        "p10-recovery-dispatch-attempt:" + request.dispatchAttempt(),
                        "p10-recovery-idempotency:" + request.idempotencyKey()),
                "P10 recovery probe completed after canonical recovery boundary");
    }

    private void requireGovernance(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("governed allocation required");
        if (!request.dispatchBound()) throw new SecurityException("durable dispatch binding required");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("recovery probe worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("recovery probe authorization mismatch");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("recovery probe is read-only only");
        }
        if (!request.workSpec().requiredCapability().equals(CAPABILITY)) {
            throw new SecurityException("recovery probe capability mismatch");
        }
        mode(request.workSpec().target());
    }

    private void persistMarker(Path marker, CapabilityRequest request, Mode mode, Instant completedAt) {
        try {
            Files.createDirectories(marker.getParent());
            ObjectNode node = json.createObjectNode();
            node.put("schemaVersion", 1);
            node.put("objectiveId", request.objectiveId());
            node.put("target", request.workSpec().target());
            node.put("mode", mode.name());
            node.put("dispatchReference", request.dispatchReference());
            node.put("dispatchAttempt", request.dispatchAttempt());
            node.put("workerId", request.allocatedWorkerId());
            node.put("assignmentReference", request.assignmentReference());
            node.put("authorizationReference", request.authorizationReference());
            node.put("idempotencyKey", request.idempotencyKey());
            node.put("completedAt", completedAt.toString());
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    .getBytes(StandardCharsets.UTF_8);
            Path temp = Files.createTempFile(marker.getParent(), marker.getFileName().toString(), ".tmp");
            Files.write(temp, bytes);
            try {
                Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("recovery-probe-marker-write-failed", failure);
        }
    }

    public static Path markerPath(Path root, String objectiveId, String target) {
        Objects.requireNonNull(root, "root");
        return root.resolve(shortHash(objectiveId + "\n" + target) + ".json");
    }

    private static Mode mode(String target) {
        if (target == null || !target.startsWith(TARGET_PREFIX)) {
            throw new IllegalArgumentException("recovery probe target must start with " + TARGET_PREFIX);
        }
        String remainder = target.substring(TARGET_PREFIX.length());
        String segment = remainder.contains("/") ? remainder.substring(0, remainder.indexOf('/')) : remainder;
        return switch (segment.trim().toLowerCase(Locale.ROOT)) {
            case "transient-timeout" -> Mode.TRANSIENT_TIMEOUT;
            case "restart-window" -> Mode.RESTART_WINDOW;
            case "observation-retry" -> Mode.OBSERVATION_RETRY;
            default -> throw new IllegalArgumentException("unsupported recovery probe mode: " + segment);
        };
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    enum Mode { TRANSIENT_TIMEOUT, RESTART_WINDOW, OBSERVATION_RETRY }
}
