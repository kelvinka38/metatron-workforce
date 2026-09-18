package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.FounderWorkerExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.testing.GovernanceTestHarness;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-composition acceptance for the exact production new-app Objective (2026-09-17), exercising as
 * much of the real path as this test can compose without a live sandbox/Git process:
 *
 * Objective text -&gt; deterministic planning (FounderWorkerExecutionPlanProposalService) -&gt; authority
 * binding against the real production AuthorityManifestCatalog (GovernanceTestHarness) -&gt; existing
 * canonical WORKER-GENERAL-ENGINEERING reuse (no second Worker formed) -&gt; real governed Assignment,
 * referenced on the Management Objective BEFORE the capability call that decides success/failure returns
 * -&gt; simulated fresh-new-app workspace source mutation, build, tests, runtime verification and local Git
 * evidence (the deterministic action sequence itself is unit-proven in
 * GeneralEngineeringFreshApplicationMaterializationTest and GeneralEngineeringExactObjectiveCompletionFloorTest)
 * -&gt; durable step evidence on the Objective -&gt; Assignment terminal completion.
 *
 * The Objective's own terminal COMPLETED status for MUTATING work additionally requires production's
 * separate Highway-Conformance release-evidence chain (AutonomyCoordinationService's
 * CompletionGate/GovernanceStateStore/ObservationClosureService, requiring exact source/tested/approved/
 * deployed/observed SHA correlation) -- a distinct, already-covered subsystem this Objective does not
 * even request (no remote publication, no deploy). This test does not weaken, bypass, or fake that gate;
 * it simply is not what this Objective's local-evidence acceptance floor is about, so it is left
 * unconfigured here exactly as AssignmentLifecycleObservabilityTest already documents.
 */
class GeneralEngineeringFullCompositionAcceptanceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T13:00:00Z"), ZoneOffset.UTC);
    private static final String WORKER_ID = GeneralWorkspaceAutonomousCapability.WORKER_ID;
    private static final String CAPABILITY = GeneralWorkspaceAutonomousCapability.CAPABILITY;
    private static final String OBJECTIVE_TEXT =
            "Take ownership of one governed Objective: build and deliver a complete runnable web "
                    + "application called Metatron Workforce Control Center. Assign the implementation "
                    + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                    + "tests, runtime verification, Git evidence, and terminal completion.";

    @Test
    void exactProductionNewApplicationObjectiveCompletesThroughTheRealComposedPathWithTruthfulAssignmentAttribution() {
        // 1. PLANNING: the real deterministic planner routes the exact production Objective text.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(OBJECTIVE_TEXT).orElseThrow();
        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);
        List<ExecutionWorkSpec> plan = planner.propose(
                "case:full-composition-acceptance", request,
                List.of(GeneralWorkspaceAutonomousCapability.CAPABILITY,
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));
        assertEquals(3, plan.size());
        assertTrue(plan.stream().allMatch(routed -> CAPABILITY.equals(routed.requiredCapability())));
        assertTrue(plan.stream().allMatch(routed ->
                "repository:kelvinka38/metatron-workforce-control-center".equals(routed.target())));
        assertEquals(List.of(), plan.get(0).dependsOn());
        assertEquals(List.of(plan.get(0).stepId()), plan.get(1).dependsOn());
        assertEquals(List.of(plan.get(1).stepId()), plan.get(2).dependsOn());
        assertTrue(plan.getFirst().evidenceRequirements().stream()
                .anyMatch("workspace-source:fresh-new-application"::equalsIgnoreCase),
                "only the production phase must carry the fresh-new-application source marker");
        assertTrue(plan.subList(1, plan.size()).stream().noneMatch(step -> step.evidenceRequirements().stream()
                .anyMatch("workspace-source:fresh-new-application"::equalsIgnoreCase)));

        // 2. AUTHORITY BINDING: every durable phase resolves against the same real production authority.
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        for (ExecutionWorkSpec routed : plan) {
            try {
                harness.plans.bindAuthorizedWork(
                        "objective:full-composition-acceptance", "founder", routed,
                        "FOUNDER", "authorization:full-composition-acceptance", java.util.Map.of());
            } catch (GovernanceDeniedException denied) {
                throw new AssertionError("authority binding must succeed for routed phase " + routed.stepId() + ": "
                        + denied.code() + " -- " + denied.getMessage(), denied);
            }
        }
        ExecutionWorkSpec routed = plan.getFirst();

        // 3. STAFFING: reuse the existing canonical Worker; never form a duplicate.
        WorkforceCoreService core = stagedCore();
        assertEquals(1, core.allWorkers().size());

        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicBoolean assignmentVisibleBeforeSuccess = new AtomicBoolean(false);
        AtomicInteger executionCalls = new AtomicInteger();

        // 4/5. REAL ASSIGNMENT + PHASED EXECUTION (simulated): each durable Work phase receives a real
        // governed Assignment before capability execution and emits only the evidence category that
        // belongs to that phase. Detailed Action Fabric sequencing is separately unit-proven.
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE; }
            @Override public String authorizationReference() { return GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE; }
            @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest capabilityRequest) {
                String objectiveId = capabilityRequest.objectiveId();
                assertTrue(management.get(objectiveId).assignmentRefs().contains(
                                capabilityRequest.assignmentReference()),
                        "the phase Assignment must already be referenced before capability execution returns");
                WorkforceCoreService.Assignment assignment = core.allAssignments().stream()
                        .filter(a -> capabilityRequest.assignmentReference().equals(a.assignmentId()))
                        .findFirst().orElseThrow();
                assertEquals(WORKER_ID, assignment.workerId());
                assignmentVisibleBeforeSuccess.set(true);
                executionCalls.incrementAndGet();

                String stepId = capabilityRequest.workSpec().stepId();
                List<String> evidence;
                if (stepId.endsWith("-produce")) {
                    evidence = List.of(
                            "workspace-source:fresh-new-application:path=src/App.java",
                            "worker-assignment evidence attributed to " + WORKER_ID);
                } else if (stepId.endsWith("-verify")) {
                    evidence = List.of(
                            "workspace-build:workspace.build.run:success",
                            "workspace-test:workspace.test.run:success",
                            "workspace-runtime:workspace.process.run:success",
                            "worker-assignment evidence attributed to " + WORKER_ID);
                } else if (stepId.endsWith("-deliver")) {
                    evidence = List.of(
                            "workspace-git:workspace.git.run:commit",
                            "worker-assignment evidence attributed to " + WORKER_ID);
                } else {
                    throw new AssertionError("unexpected phased Work step: " + stepId);
                }
                return new CapabilityResult(true, capabilityRequest.allocatedWorkerId(),
                        capabilityRequest.assignmentReference(), "work-metatron-workforce-control-center",
                        evidence, "PASS:" + stepId);
            }
        };
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, harness.admission, CLOCK, null, new ExecutionAttemptService(),
                new RuntimeCapacityCoordinator(new RuntimeRegistry()), harness.plans, harness.attemptBindings, harness.gate);
        governed.onAssignmentCreated((objectiveId, assignmentId) -> management.addAssignmentReference(
                objectiveId, management.get(objectiveId).ownerWorkerId(), assignmentId, CLOCK.instant()));

        try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(governed), new AutonomyCoordinationService(), CLOCK,
                "runner-full-composition-acceptance", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
            HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                    management, List.of(governed), runner, "worker-head", CLOCK);

            var receipt = ingress.submit(
                    "human-primary", "metatron", "case-full-composition-acceptance",
                    "conversation-full-composition-acceptance", "message-full-composition-acceptance",
                    "workplace", requestWithExactPlan(plan));
            runner.runOnce();

            String objectiveId = receipt.objectiveId();
            assertTrue(assignmentVisibleBeforeSuccess.get(), "capability execution must have actually run");
            assertEquals(plan.size(), executionCalls.get(),
                    "each durable phase must execute exactly once");

            // 6. REAL ASSIGNMENT TERMINAL COMPLETION: each phase owns a real terminal Assignment.
            assertEquals(plan.size(), core.allAssignments().size());
            assertTrue(core.allAssignments().stream().allMatch(assignment ->
                    WORKER_ID.equals(assignment.workerId())
                            && assignment.status() == WorkforceCoreService.AssignmentStatus.COMPLETED));
            assertEquals(plan.size(), management.get(objectiveId).assignmentRefs().size());
            assertTrue(core.allAssignments().stream().allMatch(assignment ->
                    management.get(objectiveId).assignmentRefs().contains(assignment.assignmentId())));
            assertNotEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, routed.target(),
                    "governed execution target must never be the performer's own Worker identity");

            // 7. STEP EVIDENCE: every requested acceptance category landed as durable Objective evidence.
            AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElseThrow();
            assertEquals(plan.size(), work.completedStepIds().size());
            assertEquals(plan.stream().map(ExecutionWorkSpec::stepId).collect(java.util.stream.Collectors.toSet()),
                    work.completedStepIds().stream().collect(java.util.stream.Collectors.toSet()));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("workspace-source:")));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("workspace-build:")));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("workspace-test:")));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("workspace-runtime:")));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("workspace-git:")));
            assertTrue(work.evidenceReferences().stream().anyMatch(ref ->
                    ref.startsWith("autonomous-step:") && ref.contains(":worker=" + WORKER_ID)),
                    "step evidence must attribute the real performer from durable execution attribution");

            // 8. TRUTHFUL MONITOR: never UNASSIGNED, real performer resolves from durable attribution.
            String card = new WorkCardRenderer(management, core).render(objectiveId);
            assertFalse(card.contains("UNASSIGNED"));
            assertTrue(card.contains(WORKER_ID));

            // No duplicate Worker was ever formed and no cognitive-work workaround was required.
            assertEquals(1, core.allWorkers().size());
            assertTrue(core.capabilities(WORKER_ID).stream()
                            .noneMatch(c -> FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(c.capabilityRef())),
                    "no worker.cognitive.work workaround must be required for General Engineering");
        }
    }

    /** The real planner's exact output, reused so the runner executes the identical governed Work graph. */
    private static NormalizedRequest requestWithExactPlan(List<ExecutionWorkSpec> routedPlan) {
        return new NormalizedRequest(
                OBJECTIVE_TEXT, WORKER_ID,
                List.of("execute autonomously under governed Workforce Assignment attribution"),
                com.metatron.workforce.interaction.intelligence.IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product",
                List.of(),
                List.of("do not claim completion before independent Observation"),
                "current", "",
                com.metatron.workforce.interaction.intelligence.IntelligenceMode.EXECUTION,
                com.metatron.workforce.interaction.intelligence.CollaborationMode.SINGLE,
                List.<com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType>of(),
                com.metatron.workforce.interaction.intelligence.DeterministicCapability.NONE,
                List.of(), List.copyOf(routedPlan), false, null,
                com.metatron.workforce.interaction.llm.LlmProvider.OPENAI, "");
    }

    private static WorkforceCoreService stagedCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:general-engineering-worker",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-general-cognitive-engineering:v1");
        core.admitWorker(WORKER_ID, "participant:general-engineering-worker");
        core.participate("participation:general-engineering-worker:metatron",
                WORKER_ID, "organization:metatron",
                "position:general-engineering-executor", "role:general-code-and-runtime-worker");
        core.attestCapability(WORKER_ID, CAPABILITY, 1.0, "evidence:general-engineering-capability-acceptance:v1");
        core.setAvailability(WORKER_ID, true, 2.0);
        return core;
    }
}