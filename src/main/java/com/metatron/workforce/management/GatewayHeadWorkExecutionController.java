package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionCommand;
import com.metatron.workforce.execution.ExecutionResult;
import com.metatron.workforce.execution.GatewayAuditCapability;
import com.metatron.workforce.interaction.knowledge.GitHubKnowledgeSource;
import com.metatron.workforce.interaction.knowledge.KnowledgeDocument;
import com.metatron.workforce.interaction.knowledge.KnowledgeQuery;
import com.metatron.workforce.work.InstitutionalWork;
import com.metatron.workforce.work.WorkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded execution bridge for a Gateway Head institutional assignment.
 * It proves Worker -> Work -> Knowledge -> admitted Execution without granting
 * any mutation authority that the worker does not already possess.
 */
@RestController
@RequestMapping("/workforce/management/gateway-head")
public final class GatewayHeadWorkExecutionController {
    private static final String REQUIRED_ROLE = "ROLE-HEAD-OF-GATEWAY";
    private static final String AUDIT_CAPABILITY = "gateway.audit.read";
    private static final String DOC_REPOSITORY = "kelvinka38/metatron-institution";
    private static final String DOC_REF = "b3516179879dc90dd482660efef71894069470c3";
    private static final List<String> DOC_PATHS = List.of(
            "06_GATEWAY/SOT.md",
            "06_GATEWAY/CONTRACTS.md",
            "06_GATEWAY/GATEWAY_CURRENT_STATE.md",
            "06_GATEWAY/GATEWAY_PRODUCTION_ARCHITECTURE.md",
            "06_GATEWAY/MASTER_EXECUTION_PLAN.md",
            "06_GATEWAY/g6/online/PRODUCTION_COMPLETION.md"
    );

    private final WorkforceCoreService core;
    private final WorkService workService;
    private final ObjectMapper objectMapper;

    public GatewayHeadWorkExecutionController(WorkforceCoreService core, WorkService workService, ObjectMapper objectMapper) {
        this.core = core;
        this.workService = workService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestHeader(value = "X-Metatron-Actor", defaultValue = "") String actor,
            @RequestParam String workerId,
            @RequestParam String workId) {
        if (!"FOUNDER".equals(actor)) {
            return ResponseEntity.status(403).body(Map.of("status", "UNAUTHORIZED", "reason", "FOUNDER actor required"));
        }

        WorkforceCoreService.Worker worker = core.worker(workerId);
        InstitutionalWork work = workService.get(workId);
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "worker not active"));
        }
        if (!workerId.equals(work.originatedByWorkerId())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "work attribution mismatch"));
        }
        if (work.status() != InstitutionalWork.Status.IN_PROGRESS) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "work must be IN_PROGRESS"));
        }

        boolean roleActive = core.participations(workerId).stream().anyMatch(p ->
                p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE && REQUIRED_ROLE.equals(p.roleRef()));
        if (!roleActive) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "active Head of Gateway role required"));
        }
        boolean auditAttested = core.capabilities(workerId).stream().anyMatch(c -> AUDIT_CAPABILITY.equals(c.capabilityRef()));
        if (!auditAttested) {
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "gateway.audit.read capability not attested"));
        }

        String githubToken = env("GITHUB_TOKEN");
        if (githubToken.isBlank()) {
            workService.block(workId, "runtime:GITHUB_TOKEN_MISSING", Instant.now());
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "runtime GitHub token unavailable"));
        }

        GitHubKnowledgeSource github = new GitHubKnowledgeSource(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(15), githubToken);
        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        StringBuilder canonical = new StringBuilder();

        for (String path : DOC_PATHS) {
            String queryPath = "/repos/" + DOC_REPOSITORY + "/contents/" + path + "?ref=" + DOC_REF;
            KnowledgeDocument doc = github.retrieve(new KnowledgeQuery(queryPath, "gateway:canonical", List.of("github.rest"), 1));
            if (doc == null) {
                String failure = "knowledge:missing:" + path;
                workService.block(workId, failure, Instant.now());
                return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "canonical Gateway document unavailable", "path", path));
            }
            try {
                JsonNode envelope = objectMapper.readTree(doc.content());
                String encoded = envelope.path("content").asText("").replace("\n", "");
                if (encoded.isBlank()) throw new IllegalStateException("GitHub content missing");
                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                String sha = envelope.path("sha").asText("");
                String ref = "github:" + DOC_REPOSITORY + "/" + path + "@" + (sha.isBlank() ? DOC_REF : sha);
                evidence.add(ref);
                canonical.append("\n\n=== ").append(path).append(" ===\n").append(decoded);
                documents.add(Map.of("path", path, "sha", sha, "bytes", decoded.getBytes(StandardCharsets.UTF_8).length, "evidence", ref));
            } catch (Exception failure) {
                String ref = "knowledge:decode-failed:" + path;
                workService.block(workId, ref, Instant.now());
                return ResponseEntity.internalServerError().body(Map.of("status", "FAILED", "reason", "canonical document decode failed", "path", path));
            }
        }

        String auditUrl = env("METATRON_GATEWAY_AUDIT_URL");
        if (auditUrl.isBlank()) {
            workService.block(workId, "gateway:METATRON_GATEWAY_AUDIT_URL_MISSING", Instant.now());
            return ResponseEntity.unprocessableEntity().body(Map.of("status", "BLOCKED", "reason", "Gateway audit endpoint unavailable", "documents", documents));
        }
        ExecutionCommand command = new ExecutionCommand("EXEC-GATEWAY-HEAD-" + Instant.now().toEpochMilli(), AUDIT_CAPABILITY, Instant.now());
        ExecutionResult audit = new GatewayAuditCapability(auditUrl, env("METATRON_GATEWAY_AUDIT_TOKEN")).execute(command);
        evidence.add("execution:" + audit.executionId());
        evidence.add("gateway:audit:" + audit.state());

        String docs = canonical.toString();
        boolean declaresAuthorityBoundary = docs.contains("AUTHORITY") || docs.contains("Authority") || docs.contains("authority");
        boolean hasProductionArchitecture = docs.contains("GATEWAY_PRODUCTION_ARCHITECTURE") || docs.contains("Production Architecture") || docs.contains("production architecture");
        boolean mutationAttested = core.capabilities(workerId).stream().anyMatch(c ->
                c.capabilityRef().startsWith("gateway.") && !c.capabilityRef().equals(AUDIT_CAPABILITY)
                        && (c.capabilityRef().contains("write") || c.capabilityRef().contains("deploy") || c.capabilityRef().contains("change")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("role", REQUIRED_ROLE);
        result.put("workId", workId);
        result.put("canonicalRef", DOC_REF);
        result.put("documentsRead", documents);
        result.put("gatewayAudit", Map.of(
                "executionId", audit.executionId(),
                "state", audit.state().name(),
                "message", audit.message(),
                "completedAt", audit.completedAt().toString()));
        result.put("authorityBoundaryObserved", declaresAuthorityBoundary);
        result.put("productionArchitectureObserved", hasProductionArchitecture);
        result.put("evidence", List.copyOf(evidence));

        if (audit.state() != com.metatron.workforce.execution.ExecutionState.COMPLETED) {
            String blocker = "gateway:audit-failed:" + audit.executionId();
            workService.block(workId, blocker, Instant.now());
            result.put("status", "BLOCKED");
            result.put("nextAction", "Repair live Gateway audit health before any implementation action.");
            return ResponseEntity.ok(result);
        }

        if (!mutationAttested) {
            String blocker = "authority:no-admitted-gateway-mutation-capability";
            workService.block(workId, blocker, Instant.now());
            result.put("status", "BLOCKED");
            result.put("nextAction", "Canonical docs and live Gateway audit were read successfully. No gateway write/deploy/change capability is attested to this worker, so mutation is fail-closed. Founder must admit a bounded Gateway mutation capability before deployment can execute.");
            result.put("workState", workService.get(workId).status().name());
            return ResponseEntity.ok(result);
        }

        result.put("status", "READY_FOR_ADMITTED_MUTATION");
        result.put("nextAction", "A separately registered Gateway mutation capability may now be admitted and executed; this bridge does not fabricate one.");
        return ResponseEntity.ok(result);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
