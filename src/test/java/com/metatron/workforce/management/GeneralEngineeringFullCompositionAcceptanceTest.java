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
        assertEquals(1, plan.size());
        ExecutionWorkSpec routed = plan.getFirst();
        assertEquals(CAPABILITY, routed.requiredCapability());
        assertEquals("repository:kelvinka38/metatron-workforce-control-center", routed.target());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, routed.consequence());
        assertTrue(routed.evidenceRequirements().stream()
                .anyMatch("workspace-source:fresh-new-application"::equalsIgnoreCase),
                "planner must mark this as fresh new-application work, not existing-repository work");

        // 2. AUTHORITY BINDING: the routed target resolves against the REAL production authority
        // manifest catalog exactly like the production incident's authority-target fix.
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        try {
            harness.plans.bindAuthorizedWork(
                    "objective:full-composition-acceptance", "founder", routed,
                    "FOUNDER", "authorization:full-composition-acceptance", java.util.Map.of());
        } catch (GovernanceDeniedException denied) {
            throw new AssertionError("authority binding must succeed for the routed new-app target: "
                    + denied.code() + " -- " + denied.getMessage(), denied);
        }

        // 3. STAFFING: reuse the existing canonical Worker; never form a duplicate.
        WorkforceCoreService core = stagedCore();
        assertEquals(1, core.allWorkers().size());

        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicBoolean assignmentVisibleBeforeSuccess = new AtomicBoolean(false);

        // 4/5. REAL ASSIGNMENT + FRESH-WORKSPACE EXECUTION (simulated): the delegate stands in for the
        // deterministic GeneralCognitiveWorkerBrain action sequence -- source mutation, build, test,
        // runtime verification and local Git evidence -- whose own deterministic contract (materialization
        // is not forced for a fresh app, the completion floor requires every one of these categories) is
        // separately unit-proven. Here it asserts truthful assignment attribution is already visible.
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE; }
            @Override public String authorizationReference() { return GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE; }
            @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest capabilityRequest) {
                String objectiveId = capabilityRequest.objectiveId();
                assertFalse(management.get(objectiveId).assignmentRefs().isEmpty(),
                        "the real Assignment must already be referenced before this capability call returns");
                WorkforceCoreService.Assignment assignment = core.allAssignments().stream()
                        .filter(a -> objectiveId.equals(a.objectiveRef())).findFirst().orElseThrow();
                assertEquals(WORKER_ID, assignment.workerId());
                assignmentVisibleBeforeSuccess.set(true);

                List<String> evidence = List.of(
                        "workspace-source:fresh-new-application:path=src/App.java",
                        "workspace-build:workspace.build.run:success",
                        "workspace-test:workspace.test.run:success",
                        "workspace-runtime:workspace.process.run:success",
                        "workspace-git:workspace.git.run:commit",
                        "worker-assignment evidence attributed to " + WORKER_ID);
                return new CapabilityResult(true, capabilityRequest.allocatedWorkerId(),
                        capabilityRequest.assignmentReference(), "work-metatron-workforce-control-center",
                        evidence, "PASS");
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
                    "workplace", requestWithExactPlan(routed));
            runner.runOnce();

            String objectiveId = receipt.objectiveId();
            assertTrue(assignmentVisibleBeforeSuccess.get(), "capability execution must have actually run");

            // 6. REAL ASSIGNMENT TERMINAL COMPLETION: the durable Assignment itself completes.
            assertEquals(1, core.allAssignments().size());
            WorkforceCoreService.Assignment assignment = core.allAssignments().getFirst();
            assertEquals(WORKER_ID, assignment.workerId());
            assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, assignment.status());
            assertTrue(management.get(objectiveId).assignmentRefs().contains(assignment.assignmentId()));
            assertNotEquals(GeneralWorkspaceAutonomousCapability.WORKER_ID, routed.target(),
                    "governed execution target must never be the performer's own Worker identity");

            // 7. STEP EVIDENCE: every requested acceptance category landed as durable Objective evidence.
            AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElseThrow();
            assertEquals(1, work.completedStepIds().size());
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

    /** The real planner's exact output, reused so the runner executes the identical governed WorkSpec. */
    private static NormalizedRequest requestWithExactPlan(ExecutionWorkSpec routed) {
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
                List.of(), List.of(routed), false, null,
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
