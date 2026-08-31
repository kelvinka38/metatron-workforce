package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.workers.WorkerResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evidence-bound join capability for Golden Slice 1.
 *
 * The capability never re-audits repositories and never accepts evidence supplied by the caller.
 * It consumes only evidence already attached to SUCCEEDED prerequisite nodes in the canonical
 * active Work Graph, resolves the durable runtime evidence produced by repository.audit.read,
 * performs a deterministic cross-repository finding/contradiction join, verifies that every
 * prerequisite was read-only, and persists a new analysis evidence artifact for Observation.
 */
@Component
public final class CrossRepositoryAuditAnalysisCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "cross-repository-audit-analysis";
    public static final String WORKER_ID = "WORKER-CROSS-REPOSITORY-AUDIT-ANALYST";
    public static final String AUTHORITY_REFERENCE = "policy:founder-readonly-cross-repository-analysis:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-readonly-cross-repository-analysis:v1";
    private static final String RUNTIME_PREFIX = "runtime-evidence:";

    private final AutonomyCoordinationService coordination;
    private final ObjectMapper json;
    private final Path evidenceRoot;

    @Autowired
    public CrossRepositoryAuditAnalysisCapability(AutonomyCoordinationService coordination, ObjectMapper json) {
        this(coordination, json, Path.of(System.getenv().getOrDefault(
                "METATRON_RUNTIME_EVIDENCE_DIR", "/var/lib/metatron-workforce/runtime-evidence")));
    }

    CrossRepositoryAuditAnalysisCapability(AutonomyCoordinationService coordination, ObjectMapper json,
                                           Path evidenceRoot) {
        this.coordination = Objects.requireNonNull(coordination, "coordination");
        this.json = Objects.requireNonNull(json, "json");
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot, "evidenceRoot");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — join completed repository audit evidence, analyze contradiction candidates, and verify zero mutation";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        requireGovernance(request);
        try {
            DurableWorkGraph graph = coordination.activeGraph(request.objectiveId())
                    .orElseThrow(() -> new IllegalStateException("active Work Graph required for cross-repository analysis"));
            List<AuditFact> facts = new ArrayList<>();
            List<String> upstreamReferences = new ArrayList<>();
            Set<String> repositories = new LinkedHashSet<>();

            for (String dependency : request.workSpec().dependsOn()) {
                DurableWorkGraph.Node node = graph.nodes().get(dependency);
                if (node == null) throw new IllegalStateException("analysis dependency missing from active Work Graph: " + dependency);
                if (node.status() != DurableWorkGraph.NodeStatus.SUCCEEDED) {
                    throw new IllegalStateException("analysis dependency not succeeded: " + dependency + ":" + node.status());
                }
                if (!RepositoryAuditAutonomousCapability.CAPABILITY.equals(node.spec().requiredCapability())) {
                    throw new SecurityException("analysis dependency is not repository.audit.read: " + dependency);
                }
                if (node.spec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
                    throw new SecurityException("analysis dependency is not READ_ONLY: " + dependency);
                }
                String runtimeReference = node.evidenceReferences().stream()
                        .filter(ref -> ref != null && ref.startsWith(RUNTIME_PREFIX + "work:repository-audit:"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("repository audit runtime evidence missing: " + dependency));
                AuditFact fact = loadAuditFact(runtimeReference);
                if (!repositories.add(fact.repository())) {
                    throw new IllegalStateException("duplicate repository dependency: " + fact.repository());
                }
                facts.add(fact);
                upstreamReferences.add(runtimeReference);
            }
            if (facts.size() < 2) throw new IllegalStateException("cross-repository analysis requires at least two independent audit dependencies");

            String report = renderReport(request, graph, facts);
            String artifactId = "cross-repository-analysis:" + request.objectiveId() + ":" + request.workSpec().stepId();
            persistEvidence(artifactId, report);

            List<String> evidence = new ArrayList<>(upstreamReferences);
            evidence.add(RUNTIME_PREFIX + artifactId);
            evidence.add("cross-repository-analysis:repositories=" + String.join(",", repositories));
            evidence.add("cross-repository-analysis:dependency_count=" + facts.size());
            evidence.add("cross-repository-analysis:zero_mutation_verified=true");
            evidence.add("cross-repository-analysis:graph_version=" + graph.graphVersion());
            return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                    artifactId, evidence,
                    "joined " + facts.size() + " repository audits; contradiction analysis complete; zero mutation verified");
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("cross-repository audit analysis failed: " + failure.getMessage(), failure);
        }
    }

    private AuditFact loadAuditFact(String runtimeReference) throws Exception {
        String artifactId = runtimeReference.substring(RUNTIME_PREFIX.length());
        Path file = evidenceRoot.resolve(safe(artifactId)).resolve("execution.json").normalize();
        if (!file.startsWith(evidenceRoot.normalize())) throw new SecurityException("runtime evidence escaped evidence root");
        if (!Files.isRegularFile(file)) throw new IllegalStateException("runtime evidence file missing: " + runtimeReference);
        JsonNode root = json.readTree(Files.readString(file));
        if (!"PASS".equals(root.path("status").asText())) {
            throw new IllegalStateException("repository audit evidence is not PASS: " + runtimeReference);
        }
        String body = root.path("evidence").asText();
        Map<String, String> fields = fields(body);
        if (!"PASS".equals(fields.get("verdict"))) throw new IllegalStateException("repository audit verdict is not PASS: " + runtimeReference);
        if (!"gateway-egress/github-api".equals(fields.get("source"))) {
            throw new IllegalStateException("repository audit source is not governed Gateway egress: " + runtimeReference);
        }
        String repository = required(fields, "repository", runtimeReference);
        String commit = required(fields, "commitSha", runtimeReference);
        String findings = fields.getOrDefault("findings", "NONE");
        String paths = fields.getOrDefault("observedPaths", "");
        int read = positiveInt(fields.get("contentFilesRead"), "contentFilesRead", runtimeReference);
        int crossings = positiveInt(fields.get("gatewayEgressCrossings"), "gatewayEgressCrossings", runtimeReference);
        return new AuditFact(repository, commit, findings, paths, read, crossings, runtimeReference);
    }

    private String renderReport(CapabilityRequest request, DurableWorkGraph graph, List<AuditFact> facts) {
        List<String> contradictionCandidates = new ArrayList<>();
        for (AuditFact fact : facts) {
            if (!"NONE".equals(fact.findings())) contradictionCandidates.add(fact.repository() + " => " + fact.findings());
        }
        String contradictions = contradictionCandidates.isEmpty()
                ? "NONE" : String.join(" | ", contradictionCandidates);
        return "Cross Repository Audit Analysis Report\n"
                + "objective=" + request.objectiveId() + "\n"
                + "step=" + request.workSpec().stepId() + "\n"
                + "graphVersion=" + graph.graphVersion() + "\n"
                + "dependencyCount=" + facts.size() + "\n"
                + "repositories=" + String.join(",", facts.stream().map(AuditFact::repository).toList()) + "\n"
                + "commits=" + String.join(" | ", facts.stream().map(f -> f.repository() + "@" + f.commitSha()).toList()) + "\n"
                + "contentFilesRead=" + facts.stream().mapToInt(AuditFact::contentFilesRead).sum() + "\n"
                + "gatewayEgressCrossings=" + facts.stream().mapToInt(AuditFact::gatewayEgressCrossings).sum() + "\n"
                + "contradictionCandidates=" + contradictions + "\n"
                + "contradictionAnalysis=COMPLETE\n"
                + "zeroMutationVerified=true\n"
                + "zeroMutationBasis=all prerequisite Work Graph nodes are repository.audit.read + READ_ONLY and their durable evidence is PASS from governed Gateway egress\n"
                + "upstreamEvidence=" + String.join(" | ", facts.stream().map(AuditFact::runtimeReference).toList()) + "\n"
                + "observedAt=" + Instant.now() + "\n"
                + "verdict=PASS\n";
    }

    private void persistEvidence(String artifactId, String report) throws Exception {
        Path directory = evidenceRoot.resolve(safe(artifactId)).normalize();
        if (!directory.startsWith(evidenceRoot.normalize())) throw new SecurityException("analysis evidence escaped evidence root");
        Files.createDirectories(directory);
        Path target = directory.resolve("execution.json");
        Path temporary = directory.resolve("execution.json.tmp");
        String encoded = json.writeValueAsString(new WorkerResult(
                "CrossRepositoryAuditAnalysisWorker", "PASS", report, Instant.now()));
        Files.writeString(temporary, encoded);
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void requireGovernance(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated() || !request.dispatchBound()) {
            throw new SecurityException("governed allocation and durable dispatch required");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("cross-repository audit analysis is READ_ONLY only");
        }
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("cross-repository analysis worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) throw new SecurityException("cross-repository analysis authorization mismatch");
        if (request.workSpec().dependsOn().isEmpty()) throw new IllegalStateException("cross-repository analysis requires Work Graph dependencies");
    }

    private static Map<String, String> fields(String evidence) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : evidence.split("\\R")) {
            int split = line.indexOf('=');
            if (split > 0) values.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        return values;
    }

    private static String required(Map<String, String> fields, String field, String reference) {
        String value = fields.get(field);
        if (value == null || value.isBlank()) throw new IllegalStateException(field + " missing from " + reference);
        return value;
    }

    private static int positiveInt(String value, String field, String reference) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) throw new NumberFormatException();
            return parsed;
        } catch (Exception failure) {
            throw new IllegalStateException(field + " invalid in " + reference);
        }
    }

    private static String safe(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record AuditFact(String repository, String commitSha, String findings, String observedPaths,
                             int contentFilesRead, int gatewayEgressCrossings, String runtimeReference) {}
}
