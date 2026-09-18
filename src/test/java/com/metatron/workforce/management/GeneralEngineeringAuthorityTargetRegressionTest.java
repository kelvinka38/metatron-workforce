package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.execution.governance.GovernancePlanService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.FounderWorkerExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.testing.GovernanceTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-cutting regression for the production incident (2026-09-17): explicit General Engineering work
 * correctly planned to execution.general.workspace still BLOCKED at governance binding with
 * "authorization-failure:AUTHORITY_UNRESOLVED:no authority manifest for target:WORKER-GENERAL-ENGINEERING",
 * because the planner bound ExecutionWorkSpec.target() -- the governed SoT resource
 * GovernancePlanService.bindAuthorizedWork() feeds into SotDiscoveryService.discover() -- to the
 * performer's own Worker identity instead. This test crosses planning -> governance -> staffing/
 * allocation using the real production AuthorityManifestCatalog (AuthorityManifestCatalog.classpath(),
 * via GovernanceTestHarness) and the real staffing path, not a planner-only unit assertion, so a
 * regression that reintroduces the Worker id as target fails here exactly as it failed in production.
 */
class GeneralEngineeringAuthorityTargetRegressionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void exactProductionObjectivePlansGovernsAndStaffsWithoutAuthorityUnresolved() {
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Take ownership of one governed Objective: build and deliver a complete runnable web "
                                + "application called Metatron Workforce Control Center. Assign the implementation "
                                + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                                + "tests, runtime verification, Git evidence, and terminal completion.")
                .orElseThrow();

        // 1. PLANNING: routes to execution.general.workspace; the governed target is never the
        // performer's own Worker identity.
        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);
        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering-authority-regression",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));
        assertEquals(3, plan.size());
        assertTrue(plan.stream().allMatch(routed ->
                GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(routed.requiredCapability())));
        assertTrue(plan.stream().noneMatch(routed ->
                GeneralWorkspaceAutonomousCapability.WORKER_ID.equals(routed.target())),
                "governed execution target must never be the performer's own Worker identity");
        // No-repository policy: every durable phase must retain the same Objective-derived authority target.
        assertTrue(plan.stream().allMatch(routed ->
                "repository:kelvinka38/metatron-workforce-control-center".equals(routed.target())));
        assertEquals(List.of(), plan.get(0).dependsOn());
        assertEquals(List.of(plan.get(0).stepId()), plan.get(1).dependsOn());
        assertEquals(List.of(plan.get(1).stepId()), plan.get(2).dependsOn());

        // 2. GOVERNANCE: every phase must bind independently against the same real production authority.
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        for (ExecutionWorkSpec routedPhase : plan) {
            GovernancePlanService.BoundPlan bound;
            try {
                bound = harness.plans.bindAuthorizedWork(
                        "objective:general-engineering-authority-regression", "founder", routedPhase,
                        "FOUNDER", "authorization:general-engineering-authority-regression", Map.of());
            } catch (GovernanceDeniedException denied) {
                throw new AssertionError("governance binding must succeed for routed General Workspace phase "
                        + routedPhase.stepId() + ", but was denied: " + denied.code() + " -- "
                        + denied.getMessage(), denied);
            }
            assertEquals("repository:metatron-canonical-four", bound.snapshot().targetScope());
        }
        ExecutionWorkSpec routed = plan.getFirst();

        // 3. STAFFING/ALLOCATION: an existing, already-staffed WORKER-GENERAL-ENGINEERING is reused for
        // the routed WorkSpec -- no second Worker formed, no worker.cognitive.work required.
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:general-engineering-worker",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-general-cognitive-engineering:v1");
        core.admitWorker(GeneralWorkspaceAutonomousCapability.WORKER_ID, "participant:general-engineering-worker");
        core.participate("participation:general-engineering-worker:metatron",
                GeneralWorkspaceAutonomousCapability.WORKER_ID, "organization:metatron",
                "position:general-engineering-executor", "role:general-code-and-runtime-worker");
        core.attestCapability(GeneralWorkspaceAutonomousCapability.WORKER_ID,
                GeneralWorkspaceAutonomousCapability.CAPABILITY, 1.0,
                "evidence:general-engineering-capability-acceptance:v1");
        core.setAvailability(GeneralWorkspaceAutonomousCapability.WORKER_ID, true, 2.0);

        AutonomousStaffingService staffing =
                new AutonomousStaffingService(core, List.of(new GeneralEngineeringStaffingPolicy()));
        AutonomousExecutionCapability routedCapabilityContract = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return routed.requiredCapability(); }
            @Override public boolean supportsWorker(String workerId) {
                return GeneralWorkspaceAutonomousCapability.WORKER_ID.equals(workerId);
            }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                throw new AssertionError("staffing/allocation reuse must not require executing the capability");
            }
        };
        AutonomousStaffingService.StaffingOutcome outcome =
                staffing.ensureStaffed(routedCapabilityContract, CLOCK.instant());

        assertTrue(outcome.staffed());
        assertEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, outcome.workerId());
        assertEquals(1, core.allWorkers().size(), "no second General Engineering Worker must be formed");
        assertTrue(core.capabilities(GeneralWorkspaceAutonomousCapability.WORKER_ID).stream()
                        .noneMatch(c -> FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(c.capabilityRef())),
                "no worker.cognitive.work capability must be required for General Engineering");
    }

    @Test
    void explicitNoncanonicalRepositoryResolvesThroughTheGenericRepositoryAuthorityWildcard() {
        // Target shape gap: a bare "owner/repo" target only resolves for the four canonical
        // repositories, which happen to also be listed as literal manifest patterns. An explicitly
        // named repository that is NOT one of those four must still resolve authority through the real
        // production AuthorityManifestCatalog's repository:* wildcard, proving the fix is a genuine
        // shape correction and not one that only works by coincidence for the canonical four.
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
                "case:general-engineering-noncanonical-authority",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));
        assertEquals(2, plan.size());
        assertTrue(plan.stream().allMatch(routed ->
                "repository:kelvinka38/new-app".equals(routed.target())));
        assertEquals(List.of(plan.getFirst().stepId()), plan.getLast().dependsOn());

        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        for (ExecutionWorkSpec routed : plan) {
            GovernancePlanService.BoundPlan bound;
            try {
                bound = harness.plans.bindAuthorizedWork(
                        "objective:general-engineering-noncanonical-authority", "founder", routed,
                        "FOUNDER", "authorization:general-engineering-noncanonical-authority", Map.of());
            } catch (GovernanceDeniedException denied) {
                throw new AssertionError("governance binding must succeed for an explicitly named, "
                        + "non-canonical repository phase through the generic repository:* authority wildcard, "
                        + "but was denied: " + denied.code() + " -- " + denied.getMessage(), denied);
            }
            assertEquals("repository:metatron-canonical-four", bound.snapshot().targetScope());
        }
    }
}
