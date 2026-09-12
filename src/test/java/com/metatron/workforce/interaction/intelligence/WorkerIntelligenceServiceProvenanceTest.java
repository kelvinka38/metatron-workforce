package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerIntelligenceServiceProvenanceTest {
    @Test
    void firstCognitiveCycleCarriesGovernedInputAndMetatronOwnedInferenceProvenance() {
        AtomicInteger externalCalls = new AtomicInteger();
        MetatronCognitionClient cognition = request -> new MetatronCognitionClient.Response(
                "{\"actionRef\":\"workspace.file.search\",\"inputs\":{\"query\":\"slugify\"},\"rationale\":\"inspect\"}",
                "metatron-node-test", "open-weight-test", 10, 5, "internal-request-1");
        IntelligenceFabric fabric = fabric(cognition, externalCalls);

        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(fabric, 1);
        WorkerIntelligenceService.Response response = service.reason(
                new WorkerIntelligenceService.Request(
                        "WORKER-GENERAL-ENGINEERING",
                        "worker.cognition",
                        "select one governed action",
                        "context",
                        List.of(),
                        "WORKER-GENERAL-ENGINEERING", "objective-1", "assignment-1", "step-1", "attempt-1"));

        assertEquals(0, externalCalls.get());
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.startsWith("worker-cognition-input:worker-cognition-")));
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.startsWith("worker-intelligence-request:worker-cognition-")));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-endpoint:metatron-node-test"));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-model:open-weight-test"));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-request:internal-request-1"));
    }

    @Test
    void autonomousWorkerCognitionRetriesOneTransientInternalCapacityWindowThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        MetatronCognitionClient cognition = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("metatron_cognition_429:capacity_exhausted");
            }
            return new MetatronCognitionClient.Response(
                    "{\"actionRef\":\"workspace.file.search\",\"inputs\":{\"query\":\"slugify\"},\"rationale\":\"inspect\"}",
                    "metatron-node-test", "open-weight-test", 10, 5, "internal-request-recovered");
        };
        IntelligenceFabric fabric = fabric(cognition, externalCalls);

        List<Long> sleeps = new ArrayList<>();
        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(fabric, 1, sleeps::add);

        WorkerIntelligenceService.Response response = service.reason(
                new WorkerIntelligenceService.Request(
                        "WORKER-GENERAL-ENGINEERING", "worker.cognition", "select one governed action", "context",
                        List.of(), "WORKER-GENERAL-ENGINEERING", "objective-1", "assignment-1", "step-1", "attempt-1"));

        assertEquals(2, calls.get());
        assertEquals(0, externalCalls.get());
        assertEquals(List.of(60_000L), sleeps);
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.contains("worker-intelligence-capacity-retry:") && v.contains("delay_ms=60000")));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-request:internal-request-recovered"));
    }

    @Test
    void liveWorkerConversationDoesNotWaitOrRetryOnInternalCapacityFailure() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        MetatronCognitionClient cognition = request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("metatron_cognition_429:capacity_exhausted");
        };
        IntelligenceFabric fabric = fabric(cognition, externalCalls);

        List<Long> sleeps = new ArrayList<>();
        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(fabric, 1, sleeps::add);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.reason(new WorkerIntelligenceService.Request(
                        "WORKER-GATEWAY-DIRECTOR",
                        "worker.live.conversation",
                        "answer naturally",
                        "meeting context",
                        List.of("institutional-source:test"),
                        "WORKER-GATEWAY-DIRECTOR", "", "", "", "")));

        assertEquals(1, calls.get());
        assertEquals(0, externalCalls.get());
        assertTrue(sleeps.isEmpty());
        assertTrue(failure.getMessage().contains("capacity_exhausted"));
    }

    private static IntelligenceFabric fabric(MetatronCognitionClient cognition, AtomicInteger externalCalls) {
        return new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> {
                    externalCalls.incrementAndGet();
                    return new LlmResponse(provider, "external-model", "forbidden", "external-request");
                },
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance(),
                new DefaultToolFabric(List.of()),
                null,
                null,
                cognition,
                new CognitionAdmissionPolicy(),
                new InMemoryInferenceConsumptionLedger());
    }
}
