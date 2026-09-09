package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerIntelligenceServiceProvenanceTest {
    @Test
    void firstCognitiveCycleCarriesGovernedInputProvenanceBeforeAnyActionEvidenceExists() {
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> new LlmResponse(
                        provider, "test-model",
                        "{\"actionRef\":\"workspace.file.search\",\"inputs\":{\"query\":\"slugify\"},\"rationale\":\"inspect\"}",
                        "provider-request-1"),
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());

        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(fabric, 1);
        WorkerIntelligenceService.Response response = service.reason(
                new WorkerIntelligenceService.Request(
                        "worker-cognitive-runtime",
                        "worker.cognition",
                        "select one governed action",
                        "{\"objectiveId\":\"objective-1\",\"availableActions\":[\"workspace.file.search\"]}",
                        List.of()));

        assertEquals("provider-request-1",
                response.evidenceReferences().stream()
                        .filter(v -> v.startsWith("worker-intelligence-provider:"))
                        .findFirst().map(v -> "provider-request-1").orElse(""));
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.startsWith("worker-cognition-input:worker-cognition-")));
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.startsWith("worker-intelligence-request:worker-cognition-")));
    }

    @Test
    void autonomousWorkerCognitionRetriesOneTransientProviderCapacityWindowThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("google_request_failed:429:quota temporarily exhausted");
                    }
                    return new LlmResponse(
                            provider, "test-model",
                            "{\"actionRef\":\"workspace.file.search\",\"inputs\":{\"query\":\"slugify\"},\"rationale\":\"inspect\"}",
                            "provider-request-recovered");
                },
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());

        List<Long> sleeps = new ArrayList<>();
        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(
                fabric, 1, sleeps::add);

        WorkerIntelligenceService.Response response = service.reason(
                new WorkerIntelligenceService.Request(
                        "worker-cognitive-runtime",
                        "worker.cognition",
                        "select one governed action",
                        "{\"objectiveId\":\"objective-1\"}",
                        List.of()));

        assertEquals(2, calls.get());
        assertEquals(List.of(60_000L), sleeps);
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.contains("worker-intelligence-capacity-retry:")
                        && v.contains("delay_ms=60000")));
        assertTrue(response.evidenceReferences().stream()
                .anyMatch(v -> v.contains("provider-request-recovered")));
    }

    @Test
    void liveMeetingConversationDoesNotWaitOrRetryOnProviderCapacityFailure() {
        AtomicInteger calls = new AtomicInteger();
        IntelligenceFabric fabric = new IntelligenceFabric(
                new IntelligencePlanner(request -> List.of(LlmProvider.GOOGLE)),
                (provider, request) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("google_request_failed:429:quota temporarily exhausted");
                },
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());

        List<Long> sleeps = new ArrayList<>();
        WorkerIntelligenceService service = WorkerIntelligenceService.backedBy(
                fabric, 1, sleeps::add);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.reason(new WorkerIntelligenceService.Request(
                        "WORKER-GATEWAY-DIRECTOR",
                        "worker.live.conversation",
                        "answer naturally",
                        "meeting context",
                        List.of("institutional-source:test"))));

        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
        assertTrue(failure.getMessage().contains("429"));
    }
}
