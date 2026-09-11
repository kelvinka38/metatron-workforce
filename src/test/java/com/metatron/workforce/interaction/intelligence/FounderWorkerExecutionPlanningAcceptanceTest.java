package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FounderWorkerExecutionPlanningAcceptanceTest {

    @Test
    void explicitTelegramStyleAssignmentBecomesTargetedFounderWorkerWork() {
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Giao WORKER-COMPOSER-ARTIST việc này: create an original pop song concept with a strong rhythmic hook.")
                .orElseThrow();

        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        assertEquals("WORKER-COMPOSER-ARTIST", request.target());

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:composer",
                request,
                List.of(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, work.requiredCapability());
        assertEquals("WORKER-COMPOSER-ARTIST", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, work.consequence());
        assertFalse(work.acceptanceCriteria().isEmpty());
        assertFalse(work.evidenceRequirements().isEmpty());
    }
}
