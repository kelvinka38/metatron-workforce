package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.workers.WorkerResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossRepositoryAuditAnalysisCapabilityTest {
    @TempDir Path temporaryDirectory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void joinsOnlySucceededRepositoryAuditEvidenceAndPersistsAnalysis() throws Exception {
        Path evidenceRoot = temporaryDirectory.resolve("runtime-evidence");
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        ExecutionWorkSpec universal = audit("universal", "kelvinka38/universal");
        ExecutionWorkSpec institution = audit("institution", "kelvinka38/metatron-institution");
        ExecutionWorkSpec join = join(List.of("universal", "institution"));
        coordination.ensureGraph("objective-gs1", List.of(universal, institution, join), now);

        String universalRef = "runtime-evidence:work:repository-audit:universal";
        String institutionRef = "runtime-evidence:work:repository-audit:institution";
        writeAuditEvidence(evidenceRoot, universalRef, "kelvinka38/universal", "sha-universal", "NONE");
        writeAuditEvidence(evidenceRoot, institutionRef, "kelvinka38/metatron-institution", "sha-institution",
                "TODO_FIXME_MARKERS=2");

        DurableDispatch first = coordination.beginDispatch("objective-gs1", 1, "universal", now.plusSeconds(1));
        coordination.completeDispatch(first.dispatchId(), List.of(universalRef), now.plusSeconds(2));
        DurableDispatch second = coordination.beginDispatch("objective-gs1", 1, "institution", now.plusSeconds(1));
        coordination.completeDispatch(second.dispatchId(), List.of(institutionRef), now.plusSeconds(2));
        assertTrue(coordination.readyNodes("objective-gs1", 1).stream()
                .anyMatch(node -> node.spec().stepId().equals("join")));

        DurableDispatch joinDispatch = coordination.beginDispatch("objective-gs1", 1, "join", now.plusSeconds(3));
        CrossRepositoryAuditAnalysisCapability capability = new CrossRepositoryAuditAnalysisCapability(
                coordination, json, evidenceRoot);
        AutonomousExecutionCapability.CapabilityRequest request = new AutonomousExecutionCapability.CapabilityRequest(
                "founder", "organization:metatron", "objective-gs1", join,
                CrossRepositoryAuditAnalysisCapability.WORKER_ID, "assignment:analysis",
                CrossRepositoryAuditAnalysisCapability.AUTHORIZATION_REFERENCE)
                .withDispatch(joinDispatch.dispatchId(), joinDispatch.attempt());

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request);
        assertTrue(result.success());
        assertTrue(result.evidenceReferences().stream().anyMatch(ref -> ref.contains("zero_mutation_verified=true")));
        Path report = evidenceRoot.resolve(safe(result.workReference())).resolve("execution.json");
        String persisted = Files.readString(report);
        assertTrue(persisted.contains("contradictionAnalysis=COMPLETE"));
        assertTrue(persisted.contains("zeroMutationVerified=true"));
        assertTrue(persisted.contains("kelvinka38/universal"));
        assertTrue(persisted.contains("kelvinka38/metatron-institution"));
        assertTrue(persisted.contains("TODO_FIXME_MARKERS=2"));
    }

    @Test
    void failsClosedWhenPrerequisiteHasNoDurableRepositoryAuditEvidence() {
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        Instant now = Instant.parse("2026-08-31T12:00:00Z");
        ExecutionWorkSpec first = audit("one", "kelvinka38/universal");
        ExecutionWorkSpec second = audit("two", "kelvinka38/bios");
        ExecutionWorkSpec join = join(List.of("one", "two"));
        coordination.ensureGraph("objective-bad", List.of(first, second, join), now);
        DurableDispatch one = coordination.beginDispatch("objective-bad", 1, "one", now.plusSeconds(1));
        coordination.completeDispatch(one.dispatchId(), List.of("not-runtime-evidence"), now.plusSeconds(2));
        DurableDispatch two = coordination.beginDispatch("objective-bad", 1, "two", now.plusSeconds(1));
        coordination.completeDispatch(two.dispatchId(), List.of("runtime-evidence:work:repository-audit:missing"), now.plusSeconds(2));
        DurableDispatch joinDispatch = coordination.beginDispatch("objective-bad", 1, "join", now.plusSeconds(3));

        CrossRepositoryAuditAnalysisCapability capability = new CrossRepositoryAuditAnalysisCapability(
                coordination, json, temporaryDirectory.resolve("runtime-evidence"));
        AutonomousExecutionCapability.CapabilityRequest request = new AutonomousExecutionCapability.CapabilityRequest(
                "founder", "organization:metatron", "objective-bad", join,
                CrossRepositoryAuditAnalysisCapability.WORKER_ID, "assignment:analysis",
                CrossRepositoryAuditAnalysisCapability.AUTHORIZATION_REFERENCE)
                .withDispatch(joinDispatch.dispatchId(), joinDispatch.attempt());
        assertThrows(IllegalStateException.class, () -> capability.execute(request));
    }

    private void writeAuditEvidence(Path evidenceRoot, String reference, String repository,
                                    String commitSha, String findings) throws Exception {
        String artifactId = reference.substring("runtime-evidence:".length());
        Path directory = evidenceRoot.resolve(safe(artifactId));
        Files.createDirectories(directory);
        String evidence = "Repository Audit Report\n"
                + "source=gateway-egress/github-api\n"
                + "repository=" + repository + "\n"
                + "commitSha=" + commitSha + "\n"
                + "contentFilesRead=4\n"
                + "gatewayEgressCrossings=6\n"
                + "findings=" + findings + "\n"
                + "observedPaths=README.md,SOT.md\n"
                + "verdict=PASS\n";
        Files.writeString(directory.resolve("execution.json"), json.writeValueAsString(
                new WorkerResult("RepositoryAuditWorker", "PASS", evidence, Instant.now())));
    }

    private static ExecutionWorkSpec audit(String id, String target) {
        return new ExecutionWorkSpec(id, "audit " + target, target,
                RepositoryAuditAutonomousCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository audit completes"), List.of("durable repository audit evidence"));
    }

    private static ExecutionWorkSpec join(List<String> dependencies) {
        return new ExecutionWorkSpec("join", "join audit evidence and analyze contradictions", "metatron",
                CrossRepositoryAuditAnalysisCapability.CAPABILITY, dependencies, ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("all prerequisite audits are joined and contradictions analyzed", "zero repository mutation is verified"),
                List.of("durable prerequisite audit evidence", "persisted cross-repository analysis report"));
    }

    private static String safe(String value) { return value.replaceAll("[^a-zA-Z0-9._-]", "_"); }
}
