package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneralActionComposingExecutionPlanProposalServiceRecoveryTest {
    private static ExecutionWorkSpec step(String id, String capability, List<String> dependencies) {
        return new ExecutionWorkSpec(id, id, "p10-recovery://transient-timeout/test", capability, dependencies,
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("accepted"), List.of("evidence"));
    }

    private static NormalizedRequest recoveryRequest() {
        return new NormalizedRequest(
                "Use the available composite capability autonomy.recovery.probe.read and recover autonomously",
                "p10-recovery://transient-timeout/test",
                List.of(), IntelligenceDepth.FAST, "evidence-backed completion",
                List.of(), List.of("do not mutate"), "", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.of(),
                DeterministicCapability.NONE, false, null, LlmProvider.OPENAI, "");
    }

    @Test
    void explicitRecoveryCompositeDropsPlannerCreatedPseudoReportSteps() {
        List<ExecutionWorkSpec> collapsed =
                GeneralActionComposingExecutionPlanProposalService.collapseExplicitRecoveryComposite(
                        recoveryRequest(),
                        List.of("autonomy.recovery.probe.read", "execution.general.workspace"),
                        List.of(
                                step("recovery", "autonomy.recovery.probe.read", List.of()),
                                step("verify-and-report", "execution.general.workspace", List.of("recovery"))));

        assertEquals(1, collapsed.size());
        assertEquals("recovery", collapsed.getFirst().stepId());
        assertEquals("autonomy.recovery.probe.read", collapsed.getFirst().requiredCapability());
        assertEquals(List.of(), collapsed.getFirst().dependsOn());
    }

    @Test
    void ordinaryPlansAreNotCollapsed() {
        NormalizedRequest ordinary = new NormalizedRequest(
                "Audit repository", "kelvinka38/metatron-workforce",
                List.of(), IntelligenceDepth.FAST, "report", List.of(), List.of(), "", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.of(),
                DeterministicCapability.NONE, false, null, LlmProvider.OPENAI, "");
        List<ExecutionWorkSpec> plan = List.of(
                step("audit", "repository.audit.read", List.of()),
                step("report", "execution.general.workspace", List.of("audit")));

        assertEquals(plan,
                GeneralActionComposingExecutionPlanProposalService.collapseExplicitRecoveryComposite(
                        ordinary, List.of("autonomy.recovery.probe.read"), plan));
    }
}
