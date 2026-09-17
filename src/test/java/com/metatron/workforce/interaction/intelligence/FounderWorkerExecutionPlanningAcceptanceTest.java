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
        // Authority target semantics fix: target() is the governed SoT resource authority discovery
        // resolves, never the performer identity -- it must not be the literal Worker id (no authority
        // manifest is or should be keyed by a Worker). This brand-new-app Objective names no repository,
        // so the target is derived from the application it says it is building ("called Metatron
        // Workforce Control Center") under the Founder GitHub owner -- never silently defaulted onto the
        // unrelated existing kelvinka38/metatron-workforce repository merely because that repository
        // already resolves authority.
        assertNotEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, work.target(),
                "governed execution target must never be the performer's own Worker identity");
        assertEquals("repository:kelvinka38/metatron-workforce-control-center", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, work.consequence());
        assertFalse(work.acceptanceCriteria().isEmpty());
        assertFalse(work.evidenceRequirements().isEmpty());
    }

    @Test
    void explicitGeneralEngineeringReadOnlyInspectionRequestStaysReadOnly() {
        // Consequence semantics gap: explicitCanonicalGeneralEngineeringWork() must not hardcode MUTATING
        // for every explicit WORKER-GENERAL-ENGINEERING request. A genuinely read-only inspection request
        // -- inspect the repository, review existing code, analyze the build configuration, explain a test
        // failure -- must stay READ_ONLY even though it incidentally mentions "build" in passing.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Giao WORKER-GENERAL-ENGINEERING việc này: inspect repository kelvinka38/metatron-workforce, "
                                + "review the existing code, analyze the build configuration, and explain why the "
                                + "test suite is failing. Do not modify any files.")
                .orElseThrow();

        assertEquals(IntelligenceMode.EXECUTION, request.mode());
        assertEquals("WORKER-GENERAL-ENGINEERING", request.target());

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering-read-only",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, work.requiredCapability());
        assertNotEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, work.target(),
                "governed execution target must never be the performer's own Worker identity");
        assertEquals("repository:kelvinka38/metatron-workforce", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, work.consequence());
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

    @Test
    void explicitNoncanonicalRepositoryResolvesThroughTheGenericRepositoryTargetShape() {
        // Target shape gap: repositoryTargets() returns a bare "owner/repo", but
        // AuthorityManifestCatalog's repository:* wildcard only prefix-matches "repository:...". An
        // explicitly named repository that is NOT one of the four canonical repositories (which also
        // happen to be listed as literal bare patterns) must still resolve -- proving the target is the
        // canonical "repository:owner/repo" shape, not a bare repository string.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Giao WORKER-GENERAL-ENGINEERING việc này: implement the feature and commit it "
                                + "against kelvinka38/new-app.")
                .orElseThrow();

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering-noncanonical-repository",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals(GeneralWorkspaceAutonomousCapability.CAPABILITY, work.requiredCapability());
        assertEquals("repository:kelvinka38/new-app", work.target());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, work.consequence());
    }

    @Test
    void noRepositoryMutatingObjectiveNeverDefaultsOntoAnUnrelatedExistingRepository() {
        // No-repository policy gap: an Objective naming no repository must never be silently pointed at
        // an unrelated existing repository (e.g. kelvinka38/metatron-workforce) merely because that
        // repository already resolves authority. The target must instead be derived from the
        // application the Objective itself says it is building, which is self-evidently unrelated to
        // Workforce here.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Take ownership of one governed Objective: build and deliver a complete runnable "
                                + "recipe-sharing mobile app called Kitchen Companion. Assign the implementation "
                                + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                                + "tests, and terminal completion.")
                .orElseThrow();

        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);

        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering-unrelated-app",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));

        assertEquals(1, plan.size());
        ExecutionWorkSpec work = plan.getFirst();
        assertEquals("repository:kelvinka38/kitchen-companion", work.target());
        assertNotEquals("repository:kelvinka38/metatron-workforce", work.target(),
                "an unrelated new app must never be silently pointed at the existing Workforce repository");
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, work.consequence());
    }

    @Test
    void generalEngineeringSpecialCaseDeclinesWhenNeitherRepositoryNorApplicationNameIsDeterminable() {
        // If the governed target genuinely cannot be determined -- no explicit repository and no named
        // application -- the special case must decline entirely rather than invent a target. The request
        // then falls through to the generic Founder-worker path and, failing that, frontier replanning.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Giao WORKER-GENERAL-ENGINEERING việc này: do some engineering work.")
                .orElseThrow();

        List<ExecutionWorkSpec> plan = FounderWorkerExecutionPlanProposalService.explicitCanonicalGeneralEngineeringWork(
                request, List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));

        assertTrue(plan.isEmpty());
    }
}
