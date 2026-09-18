package com.metatron.workforce.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CognitionRuntimeAssuranceObservationVerifierTest {
    private final CognitionRuntimeAssuranceObservationVerifier verifier =
            new CognitionRuntimeAssuranceObservationVerifier();

    @Test
    void supportsDeterministicCognitionAssuranceRequirements() {
        assertTrue(verifier.supports(requirement("qwen3:8b")));
        ObservationRequirement unrelated = new ObservationRequirement(
                "r2", "objective-test", "step", "criterion", "",
                "repository exists",
                List.of("repository evidence"), Instant.parse("2026-09-18T00:00:00Z"));
        assertFalse(verifier.supports(unrelated));
    }

    @Test
    void supportsAndPassesEveryCriterionInCognitionAssuranceStep() {
        List<String> criteria = List.of(
                "A real Worker cognition request completes through the Metatron-owned cognition endpoint.",
                "Observed model identity is strictly qwen3:8b.",
                "No external paid provider is used.");
        List<String> evidenceRequirements = List.of(
                "metatron cognition endpoint evidence",
                "metatron cognition model evidence",
                "worker intelligence request and Assignment attribution");

        for (int index = 0; index < criteria.size(); index++) {
            ObservationRequirement requirement = new ObservationRequirement(
                    "objective-test:observation:step-1:criterion:" + (index + 1),
                    "objective-test", "step-1", "step-1:criterion:" + (index + 1), "qwen3:8b",
                    criteria.get(index), evidenceRequirements, Instant.parse("2026-09-18T00:00:00Z"));
            assertTrue(verifier.supports(requirement));
            assertEquals(ObservationReport.CriterionResult.PASS,
                    verifier.observe(requirement, evidence("qwen3:8b", "ollama"), Instant.now())
                            .orElseThrow().criterionResult());
        }
    }

    @Test
    void requiresTheFullCognitionAssuranceEvidenceContract() {
        ObservationRequirement partial = new ObservationRequirement(
                "r-partial", "objective-test", "step-1", "step-1:criterion:2", "qwen3:8b",
                "Observed model identity is strictly qwen3:8b.",
                List.of("metatron cognition model evidence"), Instant.parse("2026-09-18T00:00:00Z"));
        assertFalse(verifier.supports(partial));
    }

    @Test
    void passesCompleteMetatronOwnedWorkerCognitionEvidence() {
        ObservationReport report = verifier.observe(
                requirement("qwen3:8b"), evidence("qwen3:8b", "ollama"),
                Instant.parse("2026-09-18T00:01:00Z")).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals(ObservationReport.Quality.HIGH, report.quality());
        assertEquals(1.0, report.confidence());
        assertTrue(report.observedState().contains("Metatron-owned Worker cognition"));
    }

    @Test
    void remainsPendingWhenRequiredDurableEvidenceIsMissing() {
        List<String> incomplete = List.of(
                "metatron-cognition-endpoint:metatron-cognition-node:sha:ollama",
                "metatron-cognition-model:qwen3:8b");
        assertTrue(verifier.observe(
                requirement("qwen3:8b"), incomplete, Instant.now()).isEmpty());
    }

    @Test
    void failsClosedOnModelOrProviderConflict() {
        ObservationReport wrongModel = verifier.observe(
                requirement("qwen3:8b"), evidence("qwen3:14b", "ollama"), Instant.now()).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.FAIL, wrongModel.criterionResult());

        ObservationReport wrongProvider = verifier.observe(
                requirement("qwen3:8b"), evidence("qwen3:8b", "gemini"), Instant.now()).orElseThrow();
        assertEquals(ObservationReport.CriterionResult.FAIL, wrongProvider.criterionResult());
    }

    private static ObservationRequirement requirement(String target) {
        return new ObservationRequirement(
                "objective-test:observation:step:criterion:1",
                "objective-test",
                "verify-worker-cognition-runtime",
                "verify-worker-cognition-runtime:criterion:1",
                target,
                "A real Worker cognition request completes through the Metatron-owned cognition endpoint",
                List.of(
                        "Metatron cognition endpoint routing evidence",
                        "Metatron cognition model identity verification trace showing qwen3:8b",
                        "Worker intelligence request and assignment attribution record"),
                Instant.parse("2026-09-18T00:00:00Z"));
    }

    private static List<String> evidence(String model, String provider) {
        return List.of(
                "metatron-cognition-endpoint:metatron-cognition-node:sha:ollama",
                "metatron-cognition-model:" + model,
                "metatron-cognition-request:req-1",
                "worker-cognition-evidence;provider=" + provider + ";model=" + model
                        + ";latency_ms=10;fallbackOccurred=false;providerAttempts=[]"
                        + ";objective_id=objective-test;assignment_id=assignment-1"
                        + ";worker_id=WORKER-GENERAL-ENGINEERING",
                "worker-intelligence-request:req-1",
                "cognition-assurance:external-paid-provider-observed=false",
                "cognition-assurance:result=PASS");
    }
}
