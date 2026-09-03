package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneralActionComposingExecutionPlanProposalServiceJoinTest {
    private static ExecutionWorkSpec step(String id, String capability, List<String> dependencies) {
        return new ExecutionWorkSpec(id, id, "target", capability, dependencies,
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("accepted"), List.of("evidence"));
    }

    @Test
    void invalidCrossRepositoryJoinOverRecoveryProbeIsRemoved() {
        List<ExecutionWorkSpec> sanitized =
                GeneralActionComposingExecutionPlanProposalService.removeInvalidCrossRepositoryAuditJoins(List.of(
                        step("recovery", "autonomy.recovery.probe", List.of()),
                        step("analysis", "cross-repository-audit-analysis", List.of("recovery")),
                        step("report", "report.render", List.of("analysis"))));

        assertEquals(List.of("recovery", "report"), sanitized.stream().map(ExecutionWorkSpec::stepId).toList());
        assertEquals(List.of("recovery"), sanitized.get(1).dependsOn());
    }

    @Test
    void validJoinOverTwoRepositoryAuditsIsPreserved() {
        List<ExecutionWorkSpec> plan = List.of(
                step("audit-a", "repository.audit.read", List.of()),
                step("audit-b", "repository.audit.read", List.of()),
                step("analysis", "cross-repository-audit-analysis", List.of("audit-a", "audit-b")));

        List<ExecutionWorkSpec> sanitized =
                GeneralActionComposingExecutionPlanProposalService.removeInvalidCrossRepositoryAuditJoins(plan);

        assertEquals(plan, sanitized);
    }
}
