package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNotEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, work.requiredCapability(),
                "a generic Founder-defined cognitive Worker must never escape into general workspace execution");
        assertFalse(work.acceptanceCriteria().isEmpty());
        assertFalse(work.evidenceRequirements().isEmpty());
    }

    @Test
    void explicitGeneralEngineeringImplementationRequestUsesRealExecutionCapability() {
        // Root-cause regression test: reproduces the exact production incident (2026-09-16) where an
        // Objective explicitly assigned to WORKER-GENERAL-ENGINEERING for real implementation work was
        // downgraded into the generic Founder-defined cognitive-work shortcut, then BLOCKED with
        // capacity-unavailable:worker.cognitive.work -- a capability General Engineering was never
        // staffed for. It must route through its own real, already-governed execution capability
        // instead.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Take ownership of one governed Objective: build and deliver a complete runnable web "
                                + "application called Metatron Workforce Control Center. Assign the implementation "
                                + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                                + "tests, runtime verification, Git evidence, and terminal completion.")
                .orElseThrow();

        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        // This phrasing matches the "take ownership of one governed ... objective:" grammar, not the
        // "assign/delegate/giao ..." explicit-worker-assignment grammar -- target() is legitimately
        // blank here (unchanged interpreter behavior); the Worker reference is found via the objective
        // text itself, exactly as it was in the real production incident.
        assertEquals("", request.target());

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, work.requiredCapability());
        assertNotEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, work.requiredCapability(),
                "General Engineering implementation work must not be downgraded to the cognitive-work shortcut");
        assertEquals("WORKER-GENERAL-ENGINEERING", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, work.consequence());
        assertFalse(work.acceptanceCriteria().isEmpty());
        assertFalse(work.evidenceRequirements().isEmpty());
    }

    @Test
    void arbitraryFounderDefinedWorkerWithATechnicalSoundingNameStillUsesCognitiveWorkOnly() {
        // Preserve authority boundary: only the literal canonical WORKER-GENERAL-ENGINEERING identity is
        // special-cased. A different, arbitrary Founder-defined Worker explicitly named in a
        // coding-sounding prompt must not automatically receive general workspace execution authority.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Giao WORKER-CODE-REVIEWER việc này: review this pull request and suggest improvements.")
                .orElseThrow();

        assertEquals("WORKER-CODE-REVIEWER", request.target());

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:code-reviewer",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, work.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, work.consequence());
        assertEquals("WORKER-CODE-REVIEWER", work.target());
    }

    @Test
    void generalEngineeringSpecialCaseRequiresTheRealCapabilityToBeAvailable() {
        // If execution.general.workspace is not actually registered/available, the special case must
        // not fabricate a step for a capability that does not exist -- it falls through exactly like the
        // generic path already does when its own capability is unavailable.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Assign this to WORKER-GENERAL-ENGINEERING: build and deliver a complete runnable web application.")
                .orElseThrow();

        List<ExecutionWorkSpec> plan = FounderWorkerExecutionPlanProposalService
                .explicitCanonicalGeneralEngineeringWork(request, List.of());

        assertTrue(plan.isEmpty());
    }
}
