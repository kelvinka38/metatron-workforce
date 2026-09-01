package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrentExternalCaseIsolationTest {

    @Test
    void currentExternalObservationStartsNewCaseEvenIfSemanticProviderRequestsContinue() {
        IntelligenceCase existing = existingCase("prior bounded subject");
        NormalizedRequest currentExternal = request(
                "different current subject",
                true,
                CaseContinuity.CONTINUE);

        assertTrue(IntelligenceCaseContinuityPolicy.startsNewCase(existing, currentExternal));
    }

    @Test
    void repeatedCurrentObservationAlsoStartsNewCaseForFreshEvidenceIsolation() {
        IntelligenceCase existing = existingCase("same current subject");
        NormalizedRequest currentExternal = request(
                "same current subject",
                true,
                CaseContinuity.CONTINUE);

        assertTrue(IntelligenceCaseContinuityPolicy.startsNewCase(existing, currentExternal));
    }

    @Test
    void nonFreshConversationMayStillContinueBoundedCase() {
        IntelligenceCase existing = existingCase("same analytical subject");
        NormalizedRequest continuation = request(
                "same analytical subject",
                false,
                CaseContinuity.CONTINUE);

        assertFalse(IntelligenceCaseContinuityPolicy.startsNewCase(existing, continuation));
    }

    private static IntelligenceCase existingCase(String objective) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new IntelligenceCase(
                "case-existing",
                "conversation:test",
                "human:test",
                objective,
                IntelligenceDepth.ANALYZE,
                IntelligenceCaseStatus.RESULT_READY,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "prior conclusion", "", List.of(), now, now);
    }

    private static NormalizedRequest request(String objective, boolean fresh, CaseContinuity continuity) {
        return new NormalizedRequest(
                objective,
                "",
                List.of(),
                IntelligenceDepth.ANALYZE,
                "direct natural-language answer",
                List.of(),
                List.of(),
                "",
                "",
                IntelligenceMode.REASONING,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                fresh,
                null,
                LlmProvider.GOOGLE,
                continuity,
                "");
    }
}
