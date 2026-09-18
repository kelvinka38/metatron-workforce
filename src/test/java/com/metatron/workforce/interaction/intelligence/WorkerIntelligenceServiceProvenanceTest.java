package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
                "metatron-node-test", "gemini-2.5-flash", 10, 5, "internal-request-1",
                "gemini", 37L, true, List.of("ollama=timeout"));
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
        assertTrue(response.evidenceReferences().contains("metatron-cognition-model:gemini-2.5-flash"));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-request:internal-request-1"));
        assertTrue(response.evidenceReferences().contains(
                "worker-cognition-evidence;provider=gemini;model=gemini-2.5-flash;latency_ms=37"
                        + ";fallbackOccurred=true;providerAttempts=[ollama=timeout]"
                        + ";objective_id=objective-1;assignment_id=assignment-1"
                        + ";worker_id=WORKER-GENERAL-ENGINEERING"));
    }

    @Test
    void autonomousWorkerCognitionRetriesOneTransportResetThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        MetatronCognitionClient cognition = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("metatron cognition request failed",
                        new IOException("Connection reset by peer"));
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
        assertEquals(List.of(5_000L), sleeps);
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.contains("worker-intelligence-capacity-retry:") && v.contains("delay_ms=5000")));
        assertTrue(response.evidenceReferences().contains("metatron-cognition-request:internal-request-recovered"));
    }

    @Test
    void autonomousWorkerCognitionDoesNotReplayCompletedNode502Chain() {
        assertTerminalNodeFailureNotRetried(502, "all_providers_failed");
    }

    @Test
    void autonomousWorkerCognitionDoesNotReplayExpiredNode504Chain() {
        assertTerminalNodeFailureNotRetried(504, "cognition_deadline_exhausted");
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

    private static void assertTerminalNodeFailureNotRetried(int statusCode, String errorCode) {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger externalCalls = new AtomicInteger();
        MetatronCognitionClient cognition = request -> {
            calls.incrementAndGet();
            throw new HttpMetatronCognitionClient.MetatronCognitionHttpException(statusCode, errorCode);
        };
        IntelligenceFabric fabric = fabric(cognition, externalCalls);
        List<Long> sleeps = new ArrayList<>();
        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(fabric, 1, sleeps::add);

        HttpMetatronCognitionClient.MetatronCognitionHttpException failure = assertThrows(
                HttpMetatronCognitionClient.MetatronCognitionHttpException.class,
                () -> service.reason(new WorkerIntelligenceService.Request(
                        "WORKER-GENERAL-ENGINEERING", "worker.cognition", "select one governed action", "context",
                        List.of(), "WORKER-GENERAL-ENGINEERING", "objective-1", "assignment-1", "step-1", "attempt-1")));

        assertEquals(statusCode, failure.statusCode());
        assertEquals(errorCode, failure.errorCode());
        assertEquals(1, calls.get());
        assertEquals(0, externalCalls.get());
        assertTrue(sleeps.isEmpty());
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
