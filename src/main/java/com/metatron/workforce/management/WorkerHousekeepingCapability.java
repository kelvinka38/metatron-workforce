package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Governed Workforce composition for host storage housekeeping. Deliberately least-privilege: this
 * class can reach exactly one broker operation (commander_storage_cleanup, via its own bounded
 * commander_open/commander_close session) and nothing else -- no file write, no docker control, no
 * arbitrary exec. It is a separate class from HostCommanderAutonomousCapability rather than a shared
 * "workerId" parameter on that class, specifically so a compromised or misconfigured Housekeeping
 * dispatch cannot reach any broker surface beyond storage cleanup, regardless of what
 * HostCommanderAutonomousCapability is later extended to do.
 *
 * host_safe_cleanup on the broker (reached via commander_storage_cleanup) already enforces the
 * "safe" boundary this Worker must never cross: apt cache, bounded journal vacuum, dangling image
 * prune, and build cache older than a bound. It never touches containers, named volumes, workspace
 * data, or credentials -- confirmed by reading the broker source earlier in this incident response.
 * This capability does not duplicate that logic or invent a second cleanup mechanism; it is the
 * governed Worker-driven front door to the one that was already manually verified safe today.
 */
@Component
public final class WorkerHousekeepingCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "host.storage.housekeeping";
    public static final String WORKER_ID = "WORKER-HOUSEKEEPING";
    public static final String AUTHORITY_REFERENCE = "policy:founder-housekeeping-workspace:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-housekeeping-workspace:v1";
    private static final int DEFAULT_JOURNAL_MAX_MB = 500;
    private static final int DEFAULT_BUILDER_UNTIL_HOURS = 24;
    private static final int MAX_TRANSPORT_BYTES = 3 * 1024 * 1024;

    private final ObjectMapper json;
    private final String sshKey;
    private final String sshTarget;

    @Autowired
    public WorkerHousekeepingCapability(ObjectMapper json) {
        this(json,
                System.getenv().getOrDefault("METATRON_COMMANDER_SSH_KEY", "/run/secrets/metatron_commander_ed25519"),
                System.getenv().getOrDefault("METATRON_COMMANDER_SSH_TARGET", "metatron-mcp@host.docker.internal"));
    }

    WorkerHousekeepingCapability(ObjectMapper json, String sshKey, String sshTarget) {
        this.json = Objects.requireNonNull(json, "json");
        this.sshKey = requireText(sshKey, "sshKey");
        this.sshTarget = requireText(sshTarget, "sshTarget");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — least-privilege governed host storage housekeeping (bounded Docker/build-cache/journal reclaim only)";
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        String semantic = semantic(request.workSpec());
        int journalMaxMb = boundedInt(semantic, "journal", DEFAULT_JOURNAL_MAX_MB, 100, 2000);
        int builderUntilHours = boundedInt(semantic, "builder", DEFAULT_BUILDER_UNTIL_HOURS, 6, 168);

        List<String> evidence = new ArrayList<>();
        Session session = open(binding(request.allocatedWorkerId()), bounded(request.workSpec().objective(), 300));
        String report;
        try {
            JsonNode cleanup = call("commander_storage_cleanup",
                    with(sessionArgs(session), "journal_max_mb", journalMaxMb, "builder_until_hours", builderUntilHours));
            report = cleanup.path("output").asText(cleanup.toString());
            require(!report.isBlank(), "housekeeping cleanup returned no report");
            evidence.add("housekeeping:storage-cleanup-verified:journal_max_mb=" + journalMaxMb
                    + ":builder_until_hours=" + builderUntilHours);
            evidence.add("housekeeping:before-after-report:" + oneLine(bounded(report, 2000)));
        } finally {
            call("commander_close", sessionArgs(session));
        }

        return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                "housekeeping:" + request.objectiveId(), List.copyOf(evidence),
                "Host storage housekeeping completed (idempotent: reports 0B reclaimed when nothing is safely eligible)\n" + report);
    }

    /** Parses an optional "journal <n>mb" / "builder <n>h" override from the objective text; falls
     * back to the safe default and clamps to a bounded range either way so a malformed or adversarial
     * objective text can never widen the blast radius. */
    private static int boundedInt(String semantic, String label, int fallback, int min, int max) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile(label + "[^0-9]{0,10}(\\d{1,6})").matcher(semantic);
        int value = matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
        return Math.max(min, Math.min(max, value));
    }

    private void requireGovernance(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated() || !request.dispatchBound()) throw new SecurityException("governed Housekeeping allocation/dispatch required");
        if (!CAPABILITY.equals(request.workSpec().requiredCapability())) throw new SecurityException("Housekeeping capability mismatch");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("Housekeeping worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) throw new SecurityException("Housekeeping authorization mismatch");
    }

    static String semantic(ExecutionWorkSpec work) {
        return (work.objective() + " " + work.target() + " " + work.acceptanceCriteria() + " " + work.evidenceRequirements())
                .toLowerCase(Locale.ROOT);
    }

    private Session open(String clientBinding, String purpose) {
        JsonNode node = call("commander_open", map("principal", "founder", "client_binding", clientBinding,
                "purpose", purpose, "scope", "maintenance", "ttl_seconds", 300));
        require(node.path("verified").asBoolean(false), "Housekeeping Commander session open not verified");
        return new Session(node.path("sessionId").asText(), node.path("fencingToken").asText(), clientBinding);
    }

    private JsonNode call(String op, Map<String, Object> args) {
        BrokerEnvelope envelope = invoke(op, args);
        if (!envelope.ok()) throw new IllegalStateException("Housekeeping broker operation failed: " + op + ":" + bounded(envelope.raw(), 1200));
        return envelope.inner();
    }

    private BrokerEnvelope invoke(String op, Map<String, Object> args) {
        try {
            byte[] input = json.writeValueAsBytes(Map.of("op", op, "args", args));
            ProcessBuilder builder = new ProcessBuilder(
                    "ssh", "-i", sshKey,
                    "-o", "BatchMode=yes",
                    "-o", "IdentitiesOnly=yes",
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "UserKnownHostsFile=/tmp/metatron-workforce-housekeeping-known-hosts",
                    "-o", "ConnectTimeout=10",
                    sshTarget);
            Process process = builder.start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()));
            process.getOutputStream().write(input);
            process.getOutputStream().close();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Housekeeping broker transport timeout: " + op);
            }
            String out = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String err = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String raw = out + (err.isBlank() ? "" : "\nstderr=" + err);
            JsonNode outer = out.isBlank() ? json.createObjectNode() : json.readTree(out);
            boolean outerOk = outer.path("ok").asBoolean(false) && process.exitValue() == 0;
            String innerText = outer.path("stdout").asText("").trim();
            JsonNode inner;
            if (innerText.isBlank()) {
                inner = json.createObjectNode();
            } else {
                try {
                    inner = json.readTree(innerText);
                } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
                    // host_safe_cleanup (reached via commander_storage_cleanup) forwards a plaintext
                    // before/after report, not JSON -- wrap it so callers can read it via output.
                    inner = json.createObjectNode().put("verified", outerOk).put("output", innerText);
                }
            }
            boolean innerVerified = inner.path("verified").asBoolean(outerOk);
            return new BrokerEnvelope(outerOk && innerVerified, inner, raw);
        } catch (Exception failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Housekeeping broker transport failure: " + op, failure);
        }
    }

    private byte[] readBounded(java.io.InputStream stream) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int n; (n = stream.read(buffer)) >= 0;) {
                total += n;
                if (total > MAX_TRANSPORT_BYTES) throw new IllegalStateException("Housekeeping broker transport output limit exceeded");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Housekeeping broker transport read failed", failure);
        }
    }

    private static Map<String, Object> sessionArgs(Session session) {
        return map("principal", "founder", "client_binding", session.binding(), "session_id", session.id(), "fencing_token", session.fence());
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(base);
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return Map.copyOf(result);
    }

    private static Map<String, Object> map(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return Map.copyOf(result);
    }

    private static String binding(String workerId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("workforce:" + workerId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String oneLine(String value) { return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String bounded(String value, int max) { String v = value == null ? "" : value.trim(); return v.length() <= max ? v : v.substring(0, max); }
    private static String requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required"); return value.trim(); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private record Session(String id, String fence, String binding) {}
    private record BrokerEnvelope(boolean ok, JsonNode inner, String raw) {}
}
