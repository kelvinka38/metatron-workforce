package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IntelligenceCaseInteractionProvenanceTest {

    @Test
    void interactionProvenanceIsDurablyBoundToCaseBeforeAcquisitionOrReasoning() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        IntelligenceCase original = new IntelligenceCase(
                "case-test", "conversation:test", "human:test", "current BTC price",
                IntelligenceDepth.FAST, IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                List.of(), List.of("https://existing.example/evidence"), List.of(), List.of(),
                List.of(), List.of(), List.of(), "", "", List.of(), now, now);

        IntelligenceCase bound = MetatronIntelligenceResponder.bindInteractionProvenance(
                original, "telegram", "telegram:update:12345");

        assertTrue(bound.evidenceReferences().contains(
                "observation:telegram:telegram:update:12345"));
        assertTrue(bound.externalInstitutionalReferences().contains(
                "telegram:telegram:update:12345"));
        assertEquals(IntelligenceCaseStatus.INFORMATION_ASSESSMENT, bound.status());
    }

    @Test
    void producedEvidenceCannotReplaceRequiredInteractionProvenance() {
        List<String> merged = MetatronIntelligenceResponder.mergeEvidenceReferences(
                List.of("observation:telegram:telegram:update:12345", "https://source-a.example"),
                List.of("https://source-b.example", "https://source-a.example"));

        assertEquals(List.of(
                "observation:telegram:telegram:update:12345",
                "https://source-a.example",
                "https://source-b.example"), merged);
    }
}
