package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.FounderWorkerExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousStaffingServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void emptyCoreIsGovernedlyStaffedThenAllocatedWithoutHumanApiSequencing() {
        WorkforceCoreService core = new WorkforceCoreService();
        TestCapability capability = new TestCapability();
        AutonomousStaffingService staffing = new AutonomousStaffingService(core, List.of(new TestPolicy(true, "authority:test")));
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                capability, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, staffing);

        var result = governed.execute(request());

        assertTrue(result.success());
        assertEquals(1, capability.effects.get());
        assertEquals(1, core.allParticipants().size());
        assertEquals(1, core.allWorkers().size());
        assertEquals(1, core.allParticipations().size());
        assertEquals("worker:test", result.workerId());
        assertTrue(core.capabilities("worker:test").stream().anyMatch(c -> c.capabilityRef().equals("test.capability")));
        assertTrue(core.qualifications("worker:test").stream().anyMatch(q -> q.qualificationRef().equals("qualification:test")));
        assertEquals(2.0, core.availability("worker:test").orElseThrow().capacity(), 0.000001);
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.equals("staffing:policy=test.capability")));
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.equals("runtime-profile-bound:runtime-profile:test")));
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.contains("runtime-actions=[capability:test.capability]")));
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().getFirst().status());
    }

    @Test
    void demandCannotMintWorkerWithoutPreexistingPolicy() {
        WorkforceCoreService core = new WorkforceCoreService();
        TestCapability capability = new TestCapability();
        AutonomousStaffingService staffing = new AutonomousStaffingService(core, List.of());
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                capability, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, staffing);

        AutonomousStaffingService.StaffingGapException failure = assertThrows(
                AutonomousStaffingService.StaffingGapException.class, () -> governed.execute(request()));
        assertEquals(AutonomousStaffingService.GapReason.POLICY_MISSING, failure.reason());
        assertTrue(core.allParticipants().isEmpty());
        assertTrue(core.allWorkers().isEmpty());
        assertEquals(0, capability.effects.get());
    }

    @Test
    void policyAuthorityMismatchFailsClosedBeforeIdentityFormation() {
        WorkforceCoreService core = new WorkforceCoreService();
        TestCapability capability = new TestCapability();
        AutonomousStaffingService staffing = new AutonomousStaffingService(core, List.of(new TestPolicy(true, "authority:other")));
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                capability, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO, staffing);

        AutonomousStaffingService.StaffingGapException failure = assertThrows(
                AutonomousStaffingService.StaffingGapException.class, () -> governed.execute(request()));
        assertEquals(AutonomousStaffingService.GapReason.AUTHORITY_ENVELOPE_MISMATCH, failure.reason());
        assertTrue(core.allParticipants().isEmpty());
        assertEquals(0, capability.effects.get());
    }

    @Test
    void existingGeneralEngineeringWorkerIsReusedForTheRoutedWorkSpecWithoutWorkerCognitiveWork() {
        // Staffing/allocation regression: the root-cause routing fix
        // (FounderWorkerExecutionPlanProposalService.explicitCanonicalGeneralEngineeringWork) routes an
        // explicit WORKER-GENERAL-ENGINEERING implementation Objective to execution.general.workspace.
        // This proves the OTHER half of that fix actually works end to end: an already-staffed
        // WORKER-GENERAL-ENGINEERING (ACTIVE Worker, ACTIVE participation, execution.general.workspace
        // capability, available capacity) is reused by the real AutonomousStaffingService/
        // GeneralEngineeringStaffingPolicy staffing path for that routed WorkSpec -- no second General
        // Engineering Worker is formed, and worker.cognitive.work is never required.
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

        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE,
                core.worker(GeneralWorkspaceAutonomousCapability.WORKER_ID).status());
        assertEquals(WorkforceCoreService.ParticipationStatus.ACTIVE,
                core.participations(GeneralWorkspaceAutonomousCapability.WORKER_ID).getFirst().status());

        // The routed WorkSpec produced by the actual root-cause fix for the exact production incident text.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(
                        "Take ownership of one governed Objective: build and deliver a complete runnable web "
                                + "application called Metatron Workforce Control Center. Assign the implementation "
                                + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                                + "tests, runtime verification, Git evidence, and terminal completion.")
                .orElseThrow();
        ExecutionPlanProposalService failIfDelegated =
                (caseId, normalized, available) -> {
                    throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
                };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);
        List<ExecutionWorkSpec> plan = planner.propose(
                "case:general-engineering-staffing-reuse",
                request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY));
        assertEquals(4, plan.size());
        assertTrue(plan.stream().allMatch(routed ->
                GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(routed.requiredCapability())));
        assertTrue(plan.stream().noneMatch(routed ->
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(routed.requiredCapability())));
        ExecutionWorkSpec routed = plan.getFirst();

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

        AutonomousStaffingService.StaffingOutcome outcome = staffing.ensureStaffed(routedCapabilityContract, CLOCK.instant());

        assertTrue(outcome.staffed());
        assertEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, outcome.workerId());
        assertEquals(1, core.allWorkers().size(), "no second General Engineering Worker must be formed");
        assertEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, core.allWorkers().getFirst().workerId());
        assertTrue(core.capabilities(GeneralWorkspaceAutonomousCapability.WORKER_ID).stream()
                .noneMatch(c -> FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(c.capabilityRef())),
                "no worker.cognitive.work capability must be required for General Engineering");
        assertTrue(core.capabilities(GeneralWorkspaceAutonomousCapability.WORKER_ID).stream()
                .anyMatch(c -> GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(c.capabilityRef())));
    }

    private static AutonomousExecutionCapability.CapabilityRequest request() {
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:test", "organization:test", "objective:test",
                new ExecutionWorkSpec("step:test", "test objective", "target:test", "test.capability", List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY));
    }

    private static final class TestPolicy implements AutonomousStaffingPolicy {
        private final boolean permitted;
        private final String authority;
        private TestPolicy(boolean permitted, String authority) { this.permitted = permitted; this.authority = authority; }
        @Override public String capabilityRef() { return "test.capability"; }
        @Override public FormationSpec formationSpec() {
            return new FormationSpec(permitted, "participant:test", WorkforceCoreService.ParticipantType.AI,
                    "provenance:test", "worker:test", "organization:test", "participation:test",
                    "position:test", "role:test", 1.0, "evidence:capability:test",
                    "qualification:test", "evidence:qualification:test", authority, 2.0,
                    "runtime-profile:test", "cost-limit:test", "lifecycle:test");
        }
    }

    private static final class TestCapability implements AutonomousExecutionCapability {
        private final AtomicInteger effects = new AtomicInteger();
        @Override public String capabilityRef() { return "test.capability"; }
        @Override public String authorityReference() { return "authority:test"; }
        @Override public String authorizationReference() { return "authorization:test"; }
        @Override public boolean supportsWorker(String workerId) { return "worker:test".equals(workerId); }
        @Override public CapabilityResult execute(CapabilityRequest request) {
            if (!request.allocated()) throw new AssertionError("effect reached without allocation");
            effects.incrementAndGet();
            return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                    "work:test", List.of("evidence:test"), "PASS");
        }
    }
}
