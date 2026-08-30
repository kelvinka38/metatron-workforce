package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase8.WorkforcePracticeCandidate;
import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceKnowledgeAdmissionServiceTest {
    @Test
    void preparesOnlyValidatedLearningForExternalKnowledgeAdmission() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T05:00:00Z"), ZoneOffset.UTC);
        IntelligenceKnowledgeAdmissionService service = new IntelligenceKnowledgeAdmissionService(clock);
        WorkforcePracticeCandidate validated = new WorkforcePracticeCandidate(
                "practice-1", List.of("worker-evidence:1", "worker-evidence:2"),
                "review before deploy", "validation:evidence-1", true);

        var prepared = service.prepare(baseCase(), validated, "worker-a", "authority:knowledge-admission");

        assertEquals(InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                prepared.boundaryRequest().contractId());
        assertEquals("authority:knowledge-admission", prepared.boundaryRequest().authorityReference());
        assertEquals("workforce-practice:practice-1", prepared.packageToAdmit().learningCandidateReference());
        assertEquals("validation:evidence-1", prepared.packageToAdmit().validationEvidenceReference());
    }

    @Test
    void refusesUnvalidatedLearningCandidate() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T05:00:00Z"), ZoneOffset.UTC);
        IntelligenceKnowledgeAdmissionService service = new IntelligenceKnowledgeAdmissionService(clock);
        WorkforcePracticeCandidate candidate = new WorkforcePracticeCandidate(
                "practice-1", List.of("worker-evidence:1", "worker-evidence:2"),
                "pattern", null, false);

        assertThrows(IllegalStateException.class, () ->
                service.prepare(baseCase(), candidate, "worker-a", "authority:knowledge-admission"));
    }

    @Test
    void linksKnowledgeOnlyAfterSuccessfulExternalAdmission() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T05:00:00Z"), ZoneOffset.UTC);
        IntelligenceKnowledgeAdmissionService service = new IntelligenceKnowledgeAdmissionService(clock);
        BoundaryProvenance provenance = new BoundaryProvenance(
                "knowledge", "evidence:admission", Instant.parse("2026-08-30T05:01:00Z"));
        BoundaryResult admitted = new BoundaryResult(
                "knowledge-result-1", InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                BoundaryStatus.SUCCESS, "knowledge:item-1", "authority:knowledge-admission", provenance);

        IntelligenceCase updated = service.linkAdmissionDecision(baseCase(), admitted, "knowledge:item-1");

        assertTrue(updated.externalInstitutionalReferences().contains("knowledge:item-1"));
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, updated.status());
    }

    private static IntelligenceCase baseCase() {
        Instant now = Instant.parse("2026-08-30T04:00:00Z");
        return new IntelligenceCase(
                "case-knowledge", "conversation:1", "worker:a", "learn from outcomes",
                IntelligenceDepth.DEEP, IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "candidate pattern", "", List.of(), now, now);
    }
}
