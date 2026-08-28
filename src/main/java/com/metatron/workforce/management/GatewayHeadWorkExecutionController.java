package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.Assignment;
import com.metatron.workforce.execution.Authorization;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionCommand;
import com.metatron.workforce.execution.ExecutionRequest;
import com.metatron.workforce.execution.ExecutionResult;
import com.metatron.workforce.execution.ExecutionState;
import com.metatron.workforce.execution.GatewayAuditCapability;
import com.metatron.workforce.execution.GuidanceReceipt;
import com.metatron.workforce.execution.GuidanceRequirement;
import com.metatron.workforce.execution.PublicGuidanceService;
import com.metatron.workforce.work.InstitutionalWork;
import com.metatron.workforce.work.WorkService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded execution bridge for a Gateway Head institutional assignment.
 *
 * Required Gateway guidance is resolved from the public, read-only Workforce
 * publication and must produce provenance-bearing receipts before execution is
 * admitted. Guidance is a constraint/precondition; it never creates authority.
 */
@RestController
@RequestMapping("/workforce/management/gateway-head")
public final class GatewayHeadWorkExecutionController {
    private static final String REQUIRED_ROLE = "ROLE-HEAD-OF-GATEWAY";
    private static final String AUDIT_CAPABILITY = "gateway.audit.read";
    private static final String CANONICAL_REPOSITORY = "kelvinka38/metatron-institution";
    private static final String CANONICAL_REF = "b3516179879dc90dd482660efef71894069470c3";
    private static final String PUBLIC_BASE = "/public/docs/gateway/";
    private static final String RESOURCE_BASE = "static/public/docs/gateway/";

    private static final List<PublishedDoc> DOCS = List.of(
            new PublishedDoc("SOT.md", "06_GATEWAY/SOT.md", "b6426c31dbbf52e8cc3668fc551602066803d21a"),
            new PublishedDoc("CONTRACTS.md", "06_GATEWAY/CONTRACTS.md", "70e1e089e144d0fc9632cfb15f94d55f05e56d2b"),
            new PublishedDoc("GATEWAY_CURRENT_STATE.md", "06_GATEWAY/GATEWAY_CURRENT_STATE.md", "edcce4ad183a7f0f3d5d4943a11ee5f54aec7536"),
            new PublishedDoc("GATEWAY_PRODUCTION_ARCHITECTURE.md", "06_GATEWAY/GATEWAY_PRODUCTION_ARCHITECTURE.md", "6df7b66bfa6a27f2f42425dee1da2342710f3f6d"),
            new PublishedDoc("MASTER_EXECUTION_PLAN.md", "06_GATEWAY/MASTER_EXECUTION_PLAN.md", "5fc70b5c407511d1ad44745159f7b28044734dd1"),
            new PublishedDoc("PRODUCTION_COMPLETION.md", "06_GATEWAY/g6/online/PRODUCTION_COMPLETION.md", "5edd617eafc65e1476a91095d0c71159a7e5a05a")
    );

    private final WorkforceCoreService core;
    private final WorkService workService;

    public GatewayHeadWorkExecutionController(WorkforceCoreService core, WorkService workService) {
        this.core = core;
        this.workService = workService;
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

        List<GuidanceRequirement> requirements = DOCS.stream().map(spec -> new GuidanceRequirement(
                "gateway:" + spec.publicName(),
                PUBLIC_BASE + spec.publicName(),
                upstreamRef(spec))).toList();
        Assignment executionAssignment = new Assignment(
                work.assignmentRef() == null || work.assignmentRef().isBlank() ? "work:" + workId : work.assignmentRef(),
                workerId,
                requirements);

        List<GuidanceReceipt> guidanceReceipts;
        try {
            guidanceReceipts = new PublicGuidanceService().readRequired(executionAssignment);
            ExecutionRequest admissionRequest = new ExecutionRequest(
                    "GUIDANCE-ADMISSION-" + workId + "-" + Instant.now().toEpochMilli(),
                    executionAssignment,
                    new Authorization("founder-gateway-head:" + workerId, workerId),
                    guidanceReceipts,
                    Instant.now());
            if (new ExecutionAdmissionService().admit(admissionRequest) != ExecutionState.ADMITTED) {
                throw new IllegalStateException("guidance admission did not reach ADMITTED");
            }
        } catch (Exception failure) {
            String blocker = "guidance:required-read-failed:" + failure.getMessage();
            workService.block(workId, blocker, Instant.now());
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "status", "BLOCKED",
                    "reason", "required Gateway guidance was not satisfied",
                    "detail", String.valueOf(failure.getMessage()),
                    "knowledgeSurface", PUBLIC_BASE));
        }

        List<Map<String, Object>> documents = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        StringBuilder publishedKnowledge = new StringBuilder();

        for (PublishedDoc spec : DOCS) {
            try {
                ClassPathResource resource = new ClassPathResource(RESOURCE_BASE + spec.publicName());
                if (!resource.exists()) {
                    String failure = "public-knowledge:missing:" + spec.publicName();
                    workService.block(workId, failure, Instant.now());
                    return ResponseEntity.unprocessableEntity().body(Map.of(
                            "status", "BLOCKED",
                            "reason", "published Gateway document unavailable",
                            "path", PUBLIC_BASE + spec.publicName()));
                }
                String content;
                try (var input = resource.getInputStream()) {
                    content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (content.isBlank()) throw new IllegalStateException("published document empty");

                String upstream = upstreamRef(spec);
                String publication = "public:workforce:" + PUBLIC_BASE + spec.publicName();
                evidence.add(publication);
                evidence.add(upstream);
                publishedKnowledge.append("\n\n=== ").append(spec.sourcePath()).append(" ===\n").append(content);
                GuidanceReceipt receipt = guidanceReceipts.stream()
                        .filter(candidate -> candidate.guidanceId().equals("gateway:" + spec.publicName()))
                        .findFirst().orElseThrow();
                evidence.add("guidance-receipt:" + receipt.guidanceId() + ":sha256:" + receipt.contentSha256());
                documents.add(Map.of(
                        "path", PUBLIC_BASE + spec.publicName(),
                        "sourcePath", spec.sourcePath(),
                        "sourceCommit", CANONICAL_REF,
                        "sourceBlobSha", spec.sourceBlobSha(),
                        "contentSha256", receipt.contentSha256(),
                        "readAt", receipt.readAt().toString(),
                        "bytes", content.getBytes(StandardCharsets.UTF_8).length,
                        "evidence", publication,
                        "authority", "DERIVATIVE_NOT_SOT"));
            } catch (Exception failure) {
                String ref = "public-knowledge:read-failed:" + spec.publicName();
                workService.block(workId, ref, Instant.now());
                return ResponseEntity.internalServerError().body(Map.of(
                        "status", "FAILED",
                        "reason", "published Gateway document read failed",
                        "path", PUBLIC_BASE + spec.publicName()));
            }
        }

        String auditUrl = env("METATRON_GATEWAY_AUDIT_URL");
        if (auditUrl.isBlank()) {
            workService.block(workId, "gateway:METATRON_GATEWAY_AUDIT_URL_MISSING", Instant.now());
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "status", "BLOCKED",
                    "reason", "Gateway audit endpoint unavailable",
                    "documents", documents));
        }

        ExecutionCommand command = new ExecutionCommand("EXEC-GATEWAY-HEAD-" + Instant.now().toEpochMilli(), AUDIT_CAPABILITY, Instant.now());
        ExecutionResult audit = new GatewayAuditCapability(auditUrl, env("METATRON_GATEWAY_AUDIT_TOKEN")).execute(command);
        evidence.add("execution:" + audit.executionId());
        evidence.add("gateway:audit:" + audit.state());

        String docs = publishedKnowledge.toString();
        boolean declaresAuthorityBoundary = docs.contains("authority") || docs.contains("Authority") || docs.contains("AUTHORITY");
        boolean hasProductionArchitecture = docs.contains("Production Architecture") || docs.contains("production architecture");
        boolean mutationAttested = core.capabilities(workerId).stream().anyMatch(c ->
                c.capabilityRef().startsWith("gateway.") && !c.capabilityRef().equals(AUDIT_CAPABILITY)
                        && (c.capabilityRef().contains("write") || c.capabilityRef().contains("deploy") || c.capabilityRef().contains("change")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workerId", workerId);
        result.put("role", REQUIRED_ROLE);
        result.put("canonicalRoleSemantics", "Gateway Director");
        result.put("workId", workId);
        result.put("knowledgeSurface", PUBLIC_BASE);
        result.put("guidanceRequired", true);
        result.put("guidanceSatisfied", true);
        result.put("guidanceReceiptCount", guidanceReceipts.size());
        result.put("canonicalRepository", CANONICAL_REPOSITORY);
        result.put("canonicalRef", CANONICAL_REF);
        result.put("documentsRead", documents);
        result.put("gatewayAudit", Map.of(
                "executionId", audit.executionId(),
                "state", audit.state().name(),
                "message", audit.message(),
                "completedAt", audit.completedAt().toString()));
        result.put("authorityBoundaryObserved", declaresAuthorityBoundary);
        result.put("productionArchitectureObserved", hasProductionArchitecture);
        result.put("crossRepositoryCredentialUsed", false);
        result.put("evidence", List.copyOf(evidence));

        if (audit.state() != ExecutionState.COMPLETED) {
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
            result.put("nextAction", "Required public Gateway guidance and live Gateway audit passed. No gateway write/deploy/change capability is attested, so mutation remains fail-closed.");
            result.put("workState", workService.get(workId).status().name());
            return ResponseEntity.ok(result);
        }

        result.put("status", "READY_FOR_ADMITTED_MUTATION");
        result.put("nextAction", "Required guidance was read and admitted. A separately registered Gateway mutation capability may execute; this bridge does not fabricate authority.");
        return ResponseEntity.ok(result);
    }

    private static String upstreamRef(PublishedDoc spec) {
        return "github:" + CANONICAL_REPOSITORY + "/" + spec.sourcePath() + "@" + spec.sourceBlobSha();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private record PublishedDoc(String publicName, String sourcePath, String sourceBlobSha) {}
}
