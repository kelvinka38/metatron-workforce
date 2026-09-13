package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Workforce composition adapter for the already-governed G19 Host Commander broker. */
@Component
public final class HostCommanderAutonomousCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "host.commander.execute";
    public static final String WORKER_ID = GeneralWorkspaceAutonomousCapability.WORKER_ID;
    public static final String AUTHORITY_REFERENCE = GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE;
    public static final String AUTHORIZATION_REFERENCE = GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE;
    private static final Pattern DISPOSABLE_PATH = Pattern.compile("(/tmp/metatron-commander/[A-Za-z0-9._/-]{1,180})");
    private static final Pattern CONTAINER = Pattern.compile("(?i)(?:container\\s*[:=]?\\s*|inspect\\s+(?:docker\\s+container\\s+)?)([A-Za-z0-9][A-Za-z0-9_.-]{1,127})");
    private static final int MAX_TRANSPORT_BYTES = 3 * 1024 * 1024;

    private final ObjectMapper json;
    private final String sshKey;
    private final String sshTarget;

    @Autowired
    public HostCommanderAutonomousCapability(ObjectMapper json) {
        this(json,
                System.getenv().getOrDefault("METATRON_COMMANDER_SSH_KEY", "/run/secrets/metatron_commander_ed25519"),
                System.getenv().getOrDefault("METATRON_COMMANDER_SSH_TARGET", "metatron-mcp@host.docker.internal"));
    }

    HostCommanderAutonomousCapability(ObjectMapper json, String sshKey, String sshTarget) {
        this.json = Objects.requireNonNull(json, "json");
        this.sshKey = requireText(sshKey, "sshKey");
        this.sshTarget = requireText(sshTarget, "sshTarget");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — governed Workforce composition to the existing founder Host Commander privileged broker; no raw shell";
    }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        String semantic = semantic(request.workSpec());
        if (isAcceptanceTask(semantic)) return executeAcceptance(request, semantic);
        return executeSimple(request, semantic);
    }

    private CapabilityResult executeAcceptance(CapabilityRequest request, String semantic) {
        List<String> evidence = new ArrayList<>();
        List<String> report = new ArrayList<>();
        String binding = binding(request);
        String path = disposablePath(semantic);
        String container = semantic.contains("deploy-workforce-1") ? "deploy-workforce-1" : containerName(semantic);
        Session session = open(binding, "workforce Host Commander acceptance", "maintenance");
        report.add("1 commander_open PASS session=" + session.id());
        evidence.add("host-commander:session-open:" + session.id());

        JsonNode identity = call("commander_runtime_identity", sessionArgs(session));
        require(identity.path("verified").asBoolean(false), "runtime identity not verified");
        int generation = identity.path("generation").asInt(-1);
        String sourceSha = identity.path("sourceSha").asText("");
        String release = identity.path("release").asText("");
        report.add("2 runtime_identity PASS generation=" + generation + " active=" + identity.path("active").asText()
                + " standby=" + identity.path("standby").asText() + " release=" + release + " sourceSha=" + sourceSha);
        evidence.add("host-commander:runtime:generation=" + generation + ":release=" + release + ":sourceSha=" + sourceSha);

        call("commander_file_write", with(sessionArgs(session), "path", path, "content", "telegram-alpha"));
        report.add("3 file_create PASS path=" + path);
        JsonNode readAlpha = call("commander_file_read", with(sessionArgs(session), "path", path));
        require("telegram-alpha".equals(readAlpha.path("content").asText()), "alpha readback mismatch");
        report.add("4 file_read_alpha PASS content=telegram-alpha");

        call("commander_file_patch", with(sessionArgs(session), "path", path, "old_text", "telegram-alpha",
                "new_text", "telegram-beta", "expected_occurrences", 1));
        report.add("5 file_patch PASS telegram-alpha->telegram-beta");
        JsonNode readBeta = call("commander_file_read", with(sessionArgs(session), "path", path));
        require("telegram-beta".equals(readBeta.path("content").asText()), "beta readback mismatch");
        report.add("6 file_read_beta PASS content=telegram-beta");
        evidence.add("host-commander:file-plane:create-read-patch-verified:" + path);

        JsonNode uptime = call("commander_exec", with(sessionArgs(session), "executable", "uptime", "args", List.of()));
        require(uptime.path("verified").asBoolean(false), "uptime execution not verified");
        String uptimeOutput = oneLine(uptime.path("output").asText(""));
        report.add("7 uptime PASS result=" + uptimeOutput);
        evidence.add("host-commander:uptime:" + bounded(uptimeOutput, 300));

        JsonNode docker = call("commander_docker_inspect", with(sessionArgs(session), "container", container));
        require("running".equalsIgnoreCase(docker.path("status").asText()), "container is not running");
        report.add("8 docker_inspect PASS container=" + container + " status=" + docker.path("status").asText()
                + " health=" + docker.path("health").asText("none"));
        evidence.add("host-commander:docker:" + container + ":status=" + docker.path("status").asText()
                + ":health=" + docker.path("health").asText("none"));

        JsonNode removed = call("commander_file_remove", with(sessionArgs(session), "path", path));
        require(removed.path("verified").asBoolean(false), "file remove not verified");
        report.add("9 file_remove PASS path=" + path);
        String absence = deny("commander_file_read", with(sessionArgs(session), "path", path), "commander_file_not_regular");
        report.add("10 file_absent PASS denial=" + absence);
        evidence.add("host-commander:file-remove-verified:" + path);

        String secretDenial = deny("commander_file_read", with(sessionArgs(session), "path", "/etc/shadow"),
                "commander_secret_path_denied");
        report.add("11 secret_path_denial PASS reason=" + secretDenial);
        evidence.add("host-commander:secret-path-denied");

        call("commander_close", sessionArgs(session));
        report.add("12 commander_close PASS session=" + session.id());
        String staleDenial = deny("commander_runtime_identity", sessionArgs(session), "commander_session_not_active");
        report.add("13 stale_session_denial PASS reason=" + staleDenial);
        evidence.add("host-commander:stale-session-denied");

        String summary = "HOST COMMANDER WORKFORCE ACCEPTANCE PASS 13/13\n" + String.join("\n", report)
                + "\nTOTAL PASS=13 generation=" + generation + " sourceSha=" + sourceSha + " release=" + release;
        return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                "host-commander:" + request.objectiveId(), List.copyOf(evidence), summary);
    }

    private CapabilityResult executeSimple(CapabilityRequest request, String semantic) {
        List<String> evidence = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        Session session = open(binding(request), bounded(request.workSpec().objective(), 300),
                request.workSpec().consequence() == ExecutionWorkSpec.Consequence.MUTATING ? "maintenance" : "diagnostic");
        try {
            if (semantic.contains("runtime identity") || semantic.contains("generation") || semantic.contains("commander status")) {
                JsonNode identity = call("commander_runtime_identity", sessionArgs(session));
                outputs.add("runtime generation=" + identity.path("generation").asText() + " release=" + identity.path("release").asText()
                        + " sourceSha=" + identity.path("sourceSha").asText());
                evidence.add("host-commander:runtime-identity-verified");
            }
            if (semantic.contains("uptime")) {
                JsonNode uptime = call("commander_exec", with(sessionArgs(session), "executable", "uptime", "args", List.of()));
                outputs.add("uptime=" + oneLine(uptime.path("output").asText()));
                evidence.add("host-commander:uptime-verified");
            }
            String container = containerName(semantic);
            if (!container.isBlank() && (semantic.contains("docker") || semantic.contains("container"))) {
                JsonNode state = call("commander_docker_inspect", with(sessionArgs(session), "container", container));
                outputs.add("container=" + container + " status=" + state.path("status").asText() + " health=" + state.path("health").asText("none"));
                evidence.add("host-commander:docker-inspect:" + container);
            }
            if (outputs.isEmpty()) {
                throw new IllegalArgumentException("host commander objective is not yet expressible by the bounded Workforce adapter");
            }
        } finally {
            call("commander_close", sessionArgs(session));
        }
        return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                "host-commander:" + request.objectiveId(), List.copyOf(evidence),
                "Host Commander execution completed\n" + String.join("\n", outputs));
    }

    private Session open(String clientBinding, String purpose, String scope) {
        JsonNode node = call("commander_open", map("principal", "founder", "client_binding", clientBinding,
                "purpose", bounded(purpose, 500), "scope", scope, "ttl_seconds", 900));
        require(node.path("verified").asBoolean(false), "Commander session open not verified");
        return new Session(node.path("sessionId").asText(), node.path("fencingToken").asText(), clientBinding);
    }

    private JsonNode call(String op, Map<String, Object> args) {
        BrokerEnvelope envelope = invoke(op, args);
        if (!envelope.ok()) throw new IllegalStateException("Host Commander operation failed: " + op + ":" + bounded(envelope.raw(), 1200));
        return envelope.inner();
    }

    private String deny(String op, Map<String, Object> args, String expected) {
        BrokerEnvelope envelope = invoke(op, args);
        if (envelope.ok()) throw new IllegalStateException("Host Commander security boundary unexpectedly allowed: " + op);
        if (!envelope.raw().contains(expected)) {
            throw new IllegalStateException("Host Commander denial mismatch expected=" + expected + " actual=" + bounded(envelope.raw(), 1200));
        }
        return expected;
    }

    private BrokerEnvelope invoke(String op, Map<String, Object> args) {
        try {
            byte[] input = json.writeValueAsBytes(Map.of("op", op, "args", args));
            ProcessBuilder builder = new ProcessBuilder(
                    "ssh", "-i", sshKey,
                    "-o", "BatchMode=yes",
                    "-o", "IdentitiesOnly=yes",
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "UserKnownHostsFile=/tmp/metatron-workforce-commander-known-hosts",
                    "-o", "ConnectTimeout=10",
                    sshTarget);
            Process process = builder.start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readBounded(process.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readBounded(process.getErrorStream()));
            process.getOutputStream().write(input);
            process.getOutputStream().close();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Host Commander transport timeout: " + op);
            }
            String out = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String err = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String raw = out + (err.isBlank() ? "" : "\nstderr=" + err);
            JsonNode outer = out.isBlank() ? json.createObjectNode() : json.readTree(out);
            boolean outerOk = outer.path("ok").asBoolean(false) && process.exitValue() == 0;
            String innerText = outer.path("stdout").asText("").trim();
            JsonNode inner = innerText.isBlank() ? json.createObjectNode() : json.readTree(innerText);
            boolean innerVerified = inner.path("verified").asBoolean(outerOk);
            boolean ok = outerOk && innerVerified;
            return new BrokerEnvelope(ok, inner, raw);
        } catch (Exception failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Host Commander transport failure: " + op, failure);
        }
    }

    private byte[] readBounded(java.io.InputStream stream) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int n; (n = stream.read(buffer)) >= 0;) {
                total += n;
                if (total > MAX_TRANSPORT_BYTES) throw new IllegalStateException("Host Commander transport output limit exceeded");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (Exception failure) {
            throw new IllegalStateException("Host Commander transport read failed", failure);
        }
    }

    private void requireGovernance(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated() || !request.dispatchBound()) throw new SecurityException("governed Host Commander allocation/dispatch required");
        if (!CAPABILITY.equals(request.workSpec().requiredCapability())) throw new SecurityException("Host Commander capability mismatch");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("Host Commander worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) throw new SecurityException("Host Commander authorization mismatch");
        if (request.workSpec().consequence() == ExecutionWorkSpec.Consequence.MUTATING && !request.governanceBound()) {
            throw new SecurityException("mutating Host Commander work requires governance binding");
        }
    }

    static boolean isAcceptanceTask(String semantic) {
        return semantic.contains("host commander") && semantic.contains("uptime")
                && semantic.contains("telegram-alpha") && semantic.contains("telegram-beta")
                && semantic.contains("deploy-workforce-1") && semantic.contains("stale")
                && semantic.contains("/tmp/metatron-commander/");
    }

    static String semantic(ExecutionWorkSpec work) {
        return (work.objective() + " " + work.target() + " " + work.acceptanceCriteria() + " " + work.evidenceRequirements())
                .toLowerCase(Locale.ROOT);
    }

    private static String disposablePath(String semantic) {
        Matcher matcher = DISPOSABLE_PATH.matcher(semantic);
        return matcher.find() ? matcher.group(1) : "/tmp/metatron-commander/workforce-live-test.txt";
    }

    private static String containerName(String semantic) {
        Matcher matcher = CONTAINER.matcher(semantic);
        if (matcher.find()) return matcher.group(1);
        return semantic.contains("deploy-workforce-1") ? "deploy-workforce-1" : "";
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

    private static String binding(CapabilityRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("workforce:" + request.allocatedWorkerId()).getBytes(StandardCharsets.UTF_8));
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
