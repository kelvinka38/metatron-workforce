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

    @Test
    void normalizeRecoveryProbeTargetsFixesMalformedPlannerTarget() {
        // Root cause reproduced: the frontier planner produced a step for autonomy.recovery.probe.read
        // whose target is free text, not a "p10-recovery://..." URI -- this used to reach execution and
        // fail permanently with "recovery probe target must start with p10-recovery://", leaving the
        // Objective stuck BLOCKED forever with no self-recovery path.
        ExecutionWorkSpec malformed = new ExecutionWorkSpec(
                "probe-production-autonomy", "probe production autonomy health",
                "Probe the current autonomy and control health of Metatron production",
                "autonomy.recovery.probe.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("accepted"), List.of("evidence"));

        List<ExecutionWorkSpec> normalized = GeneralActionComposingExecutionPlanProposalService
                .normalizeRecoveryProbeTargets(List.of(malformed));

        assertEquals(1, normalized.size());
        assertEquals("p10-recovery://observation-retry/probe-production-autonomy",
                normalized.getFirst().target());
        assertEquals("autonomy.recovery.probe.read", normalized.getFirst().requiredCapability());
    }

    @Test
    void normalizeRecoveryProbeTargetsLeavesConformingTargetsUntouched() {
        List<ExecutionWorkSpec> plan = List.of(step("recovery", "autonomy.recovery.probe.read", List.of()));
        assertEquals(plan, GeneralActionComposingExecutionPlanProposalService.normalizeRecoveryProbeTargets(plan));
    }

    @Test
    void normalizeRecoveryProbeTargetsIgnoresOtherCapabilities() {
        List<ExecutionWorkSpec> plan = List.of(new ExecutionWorkSpec(
                "audit", "audit repository", "not-a-recovery-uri", "repository.audit.read", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("accepted"), List.of("evidence")));
        assertEquals(plan, GeneralActionComposingExecutionPlanProposalService.normalizeRecoveryProbeTargets(plan));
    }
}

