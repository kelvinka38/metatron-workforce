package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import com.metatron.workforce.work.InstitutionalWork;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class InstitutionalIntelligenceReferenceBridgeTest {
    private final InstitutionalIntelligenceReferenceBridge bridge = new InstitutionalIntelligenceReferenceBridge();

    @Test
    void linksObservationAsExternalReferenceAndEvidenceWithoutOwningObservation() {
        IntelligenceCase intelligenceCase = baseCase();
        BoundaryResult boundary = success(
                InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                "observation-boundary-output");

        IntelligenceCase updated = bridge.linkObservation(
                intelligenceCase, boundary, "observation:obs-1", List.of("evidence:metric-1"));

        assertTrue(updated.externalInstitutionalReferences().contains("observation:obs-1"));
        assertTrue(updated.evidenceReferences().contains("evidence:metric-1"));
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, updated.status());
    }

    @Test
    void refusesFailedKnowledgeBoundaryAndDoesNotManufactureKnowledge() {
        IntelligenceCase intelligenceCase = baseCase();
        BoundaryProvenance provenance = new BoundaryProvenance(
                "knowledge", "evidence:validation", Instant.parse("2026-08-30T03:00:00Z"));
        BoundaryResult rejected = new BoundaryResult(
                "req-knowledge", InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                BoundaryStatus.REJECTED, null, "authority:knowledge", provenance);

        assertThrows(IllegalStateException.class, () ->
                bridge.linkKnowledgeAdmission(intelligenceCase, rejected, "knowledge:item-1"));
        assertTrue(intelligenceCase.externalInstitutionalReferences().isEmpty());
    }

    @Test
    void linksCompletedWorkOutcomeAndEvidenceByReference() {
        IntelligenceCase intelligenceCase = baseCase();
        Instant now = Instant.parse("2026-08-30T03:00:00Z");
        InstitutionalWork work = new InstitutionalWork(
                "work-1", "objective:1", "org-1", "worker-a", "execute approved work",
                InstitutionalWork.Status.COMPLETED, "proposal:1", "assignment:1",
                "outcome:1", List.of("evidence:execution-1", "observation:1"), now, now.plusSeconds(60));

        IntelligenceCase updated = bridge.linkCompletedWork(intelligenceCase, work);

        assertTrue(updated.externalInstitutionalReferences().contains("work:work-1"));
        assertTrue(updated.externalInstitutionalReferences().contains("assignment:1"));
        assertTrue(updated.externalInstitutionalReferences().contains("outcome:1"));
        assertTrue(updated.evidenceReferences().contains("evidence:execution-1"));
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, updated.status());
    }

    @Test
    void refusesNonCompletedWorkAsOutcome() {
        IntelligenceCase intelligenceCase = baseCase();
        Instant now = Instant.parse("2026-08-30T03:00:00Z");
        InstitutionalWork work = new InstitutionalWork(
                "work-1", "objective:1", "org-1", "worker-a", "in progress",
                InstitutionalWork.Status.IN_PROGRESS, "proposal:1", "assignment:1",
                null, List.of(), now, now);

        assertThrows(IllegalStateException.class, () -> bridge.linkCompletedWork(intelligenceCase, work));
    }

    private static BoundaryResult success(String contract, Object output) {
        BoundaryProvenance provenance = new BoundaryProvenance(
                "phase9", "evidence:boundary", Instant.parse("2026-08-30T03:00:00Z"));
        return new BoundaryResult("req-1", contract, BoundaryStatus.SUCCESS, output,
                "authority:external", provenance);
    }

    private static IntelligenceCase baseCase() {
        Instant now = Instant.parse("2026-08-30T02:00:00Z");
        return new IntelligenceCase(
                "case-1", "conversation:1", "human:1", "evaluate result",
                IntelligenceDepth.ANALYZE, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "recommendation", "", List.of(), now, now);
    }
}
