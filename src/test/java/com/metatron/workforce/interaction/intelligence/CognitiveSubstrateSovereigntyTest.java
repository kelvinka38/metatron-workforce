package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveSubstrateSovereigntyTest {
    @Test
    void workerOriginUsesOnlyMetatronOwnedCognitionAndNeverCallsExternalEngine() {
        AtomicInteger externalCalls = new AtomicInteger();
        AtomicInteger internalCalls = new AtomicInteger();
        InMemoryInferenceConsumptionLedger ledger = new InMemoryInferenceConsumptionLedger();
        MetatronCognitionClient internal = request -> {
            internalCalls.incrementAndGet();
            assertEquals(IntelligenceOriginType.WORKER, request.origin().originType());
            assertEquals("WORKER-GENERAL-ENGINEERING", request.origin().workerId());
            return new MetatronCognitionClient.Response(
                    "{\"actionRef\":\"workspace.file.search\",\"inputs\":{\"query\":\"slugify\"},\"rationale\":\"inspect\"}",
                    "metatron-cognition-test", "open-weight-test", 100, 25, "internal-1");
        };
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> {
                    externalCalls.incrementAndGet();
                    return new LlmResponse(provider, "external-model", "forbidden", "external-1");
                },
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance(),
                new DefaultToolFabric(List.of()),
                null,
                null,
                internal,
                new CognitionAdmissionPolicy(),
                ledger);

        String requestId = "worker-request-1";
        IntelligenceRequest request = new IntelligenceRequest(
                requestId,
                "WORKER-GENERAL-ENGINEERING",
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                "select next governed action",
                "context",
                List.of("worker-cognition-input:" + requestId),
                "worker.cognition",
                IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                "work-runtime",
                "bounded",
                "",
                "structured JSON",
                List.of(),
                1,
                false,
                IntelligenceOriginContext.worker(
                        "WORKER-GENERAL-ENGINEERING", "OBJ-1", "ASG-1", "STEP-1", "ATT-1",
                        "worker.cognition", requestId));

        IntelligenceResult result = fabric.execute(request);

        assertTrue(result.text().contains("workspace.file.search"));
        assertEquals(1, internalCalls.get());
        assertEquals(0, externalCalls.get());
        assertEquals(0, ledger.workerExternalPaidCount());
        assertEquals(1, ledger.records().size());
        assertEquals(IntelligenceComputeOwner.METATRON_OWNED, ledger.records().getFirst().computeOwner());
    }

    @Test
    void workerExternalPaidAdmissionFailsClosed() {
        CognitionAdmissionPolicy policy = new CognitionAdmissionPolicy();
        IntelligenceOriginContext origin = IntelligenceOriginContext.worker(
                "WORKER-1", "OBJ-1", "ASG-1", "STEP-1", "ATT-1", "worker.cognition", "REQ-1");
        SecurityException failure = assertThrows(SecurityException.class,
                () -> policy.requireAllowed(origin, IntelligenceComputeOwner.EXTERNAL_PAID));
        assertTrue(failure.getMessage().contains("WORKER_EXTERNAL_INFERENCE_DENIED"));
    }

    @Test
    void humanOriginMayStillUseExternalFrontierPath() {
        AtomicInteger externalCalls = new AtomicInteger();
        InMemoryInferenceConsumptionLedger ledger = new InMemoryInferenceConsumptionLedger();
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> {
                    externalCalls.incrementAndGet();
                    return new LlmResponse(provider, "external-model", "human answer", "external-1");
                },
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance(),
                new DefaultToolFabric(List.of()), null, null, null,
                new CognitionAdmissionPolicy(), ledger);

        IntelligenceRequest request = new IntelligenceRequest(
                "human-1", "human:test", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                "answer", "context", List.of("evidence:test"), "general", "non-consequential",
                "fast", "bounded", "", "answer", List.of(), 1, false);

        assertEquals("human answer", fabric.execute(request).text());
        assertEquals(1, externalCalls.get());
        assertEquals(IntelligenceComputeOwner.EXTERNAL_PAID, ledger.records().getFirst().computeOwner());
    }
}
