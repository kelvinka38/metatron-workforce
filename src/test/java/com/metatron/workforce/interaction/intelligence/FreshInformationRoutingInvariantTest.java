package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FreshInformationRoutingInvariantTest {

    @Test
    void freshInformationCannotBypassAcquisitionAsFastDirectResponse() {
        NormalizedRequest request = request(IntelligenceMode.DISCUSSION, true, "Giá vàng hôm nay là ...");

        assertFalse(request.canReturnFastDirectly());
    }

    @Test
    void nonFreshCasualAnswerCanStillReturnDirectly() {
        NormalizedRequest request = request(IntelligenceMode.DISCUSSION, false, "Xin chào!");

        assertTrue(request.canReturnFastDirectly());
    }

    @Test
    void freshReasoningRemainsAnInteractionWithoutExecutionWorkPlan() {
        NormalizedRequest request = request(IntelligenceMode.REASONING, true, "");

        assertTrue(request.freshExternalDataRequired());
        assertTrue(request.executionWorkPlan().isEmpty());
        assertFalse(request.mode() == IntelligenceMode.EXECUTION);
    }

    private static NormalizedRequest request(IntelligenceMode mode, boolean fresh, String directResponse) {
        return new NormalizedRequest(
                "Answer the Human's current-information question",
                "current external reality",
                List.of(),
                IntelligenceDepth.FAST,
                "direct natural-language answer",
                List.of(),
                List.of(),
                "current",
                "",
                mode,
                CollaborationMode.SINGLE,
                List.of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(),
                fresh,
                null,
                LlmProvider.OPENAI,
                CaseContinuity.NEW,
                directResponse);
    }
}
