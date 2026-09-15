package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitionRuntimeAssuranceCapabilityTest {
    private static final String MODEL = "llama3.1:8b-instruct-q4_K_M";

    @Test
    void succeedsOnlyWithMetatronOwnedEndpointAndExactModelEvidence() {
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "worker-cognition-request-1",
                "probe-ok",
                List.of(
                        "metatron-cognition-endpoint:metatron-cognition-node-ccx33",
                        "metatron-cognition-model:" + MODEL,
                        "metatron-cognition-request:node-request-1",
                        "worker-intelligence-request:worker-cognition-request-1"));
        CognitionRuntimeAssuranceCapability capability = new CognitionRuntimeAssuranceCapability(intelligence);

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request(MODEL));

        assertTrue(result.success());
        assertTrue(result.summary().contains("COGNITION RUNTIME ASSURANCE PASS"));
        assertTrue(result.evidenceReferences().contains("cognition-assurance:result=PASS"));
        assertTrue(result.evidenceReferences().contains("cognition-assurance:external-paid-provider-observed=false"));
    }

    @Test
    void modelMismatchFailsTruthfullyAndRequiresReconciliation() {
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "worker-cognition-request-2",
                "probe-ok",
                List.of(
                        "metatron-cognition-endpoint:metatron-cognition-node",
                        "metatron-cognition-model:llama3.1:1b-instruct-q4_K_M"));
        CognitionRuntimeAssuranceCapability capability = new CognitionRuntimeAssuranceCapability(intelligence);

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request(MODEL));

        assertFalse(result.success());
        assertTrue(result.summary().contains("MODEL_MISMATCH"));
        assertTrue(result.summary().contains("reconciliation_required=true"));
        assertTrue(result.evidenceReferences().contains("cognition-assurance:result=FAIL"));
    }

    @Test
    void externalPaidProviderAttributionFailsClosed() {
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "worker-cognition-request-3",
                "probe-ok",
                List.of(
                        "metatron-cognition-endpoint:metatron-cognition-node",
                        "metatron-cognition-model:" + MODEL,
                        "worker-intelligence-provider:OPENAI:model=gpt:test=request"));
        CognitionRuntimeAssuranceCapability capability = new CognitionRuntimeAssuranceCapability(intelligence);

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request(MODEL));

        assertFalse(result.success());
        assertTrue(result.summary().contains("EXTERNAL_PAID_PROVIDER_OBSERVED"));
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(String model) {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "cognition-runtime-assurance",
                "Verify Worker cognition through Metatron-owned cognition",
                model,
                CognitionRuntimeAssuranceCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("exact model observed"),
                List.of("metatron cognition evidence"));
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human-primary",
                "organization:metatron",
                "objective:test-cognition-assurance",
                work,
                CognitionRuntimeAssuranceCapability.WORKER_ID,
                "assignment:test-cognition-assurance",
                CognitionRuntimeAssuranceCapability.AUTHORIZATION_REFERENCE);
    }
}
