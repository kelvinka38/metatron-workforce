package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
