package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.actor.ActorScopedWorkerIntelligenceService;
import com.metatron.workforce.actor.InMemoryWorkerActorStateStore;
import com.metatron.workforce.actor.WorkerActorMessage;
import com.metatron.workforce.actor.WorkerActorRuntime;
import com.metatron.workforce.actor.WorkerActorState;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.observation.FounderWorkerWorkProductObservationVerifier;
import com.metatron.workforce.observation.InMemoryObservationStateStore;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonical Worker autonomy conformance proof:
 * Human Objective -> plan -> governed Assignment -> persistent Worker actor -> execution failure ->
 * bounded runtime/dispatch recovery -> durable work product -> independent Observation -> evidence-backed completion.
 */
class WorkerActorAutonomyConformanceAcceptanceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T13:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String WORKER_ID = "WORKER-COMPOSER-ARTIST";

    @Test
    void persistentWorkerActorOwnsObjectiveExecutionRecoveryVerificationAndCompletion(@TempDir Path temp) {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        RuntimeRegistry runtimeRegistry = new RuntimeRegistry();
        AtomicInteger cognitionCalls = new AtomicInteger();

        try (WorkerActorRuntime actors = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4)) {
            RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(runtimeRegistry, actors);
            FounderDefinedWorkerFormationService formation = new FounderDefinedWorkerFormationService(
                    core, profiles, constitution, runtimes);
            formation.form("composer/artist",
                    "Own assigned creative Objectives and produce durable evidence-backed work.", NOW);

            WorkerIntelligenceService flakyProvider = request -> {
                int call = cognitionCalls.incrementAndGet();
                assertEquals(WORKER_ID, request.requester());
                assertEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, request.capability());
                assertTrue(request.context().contains("CANONICAL WORKER CONSTITUTION"));
                if (call <= 2) {
                    throw new IllegalStateException("provider-timeout-attempt-" + call);
                }
                return new WorkerIntelligenceService.Response(
                        "worker-cognition:autonomy-conformance:" + call,
                        "Original work product: a syncopated call-and-response hook with a concise verse concept.",
                        List.of("worker-cognition:autonomy-conformance:" + call));
            };
            WorkerIntelligenceService actorScoped = new ActorScopedWorkerIntelligenceService(flakyProvider, actors);

            FounderWorkerWorkProductStore products = new FounderWorkerWorkProductStore(
                    temp.resolve("products"), new ObjectMapper());
            FounderDefinedCognitiveWorkCapability delegate = new FounderDefinedCognitiveWorkCapability(
                    core, profiles, constitution, actorScoped, products);
            ExecutionAttemptService attempts = new ExecutionAttemptService();
            GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                    delegate, core, new ExecutionAdmissionService(), CLOCK,
                    null, attempts, runtimes);

            ObservationClosureService observation = new ObservationClosureService(
                    new InMemoryObservationStateStore(),
                    List.of(new FounderWorkerWorkProductObservationVerifier(products)));
            ManagementAutonomyService management = new ManagementAutonomyService();
            AutonomyCoordinationService coordination = new AutonomyCoordinationService();

            try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                    management,
                    (caseId, request, available) -> request.executionWorkPlan(),
                    List.of(governed), coordination, observation, CLOCK,
                    "runner-actor-autonomy-conformance", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
                HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                        management, List.of(governed), runner, "worker-head", CLOCK);

                var receipt = ingress.submit(
                        "human-primary", "metatron", "case-actor-autonomy-conformance",
                        "conversation-actor-autonomy-conformance", "message-actor-autonomy-conformance",
                        "workplace", request());

                runner.runOnce();

                String objectiveId = receipt.objectiveId();
                assertEquals(3, cognitionCalls.get(),
                        "two transient failures must recover autonomously and the third actor turn must complete");
                assertEquals(ManagementObjective.Status.COMPLETED, management.get(objectiveId).status());
                assertEquals(AutonomousObjectiveWork.Status.COMPLETED,
                        management.findAutonomousWork(objectiveId).orElseThrow().status());
                assertEquals(ObservationClosureService.Verdict.PASSED, observation.verdict(objectiveId));
                assertTrue(management.get(objectiveId).evidenceRefs().stream()
                        .anyMatch(ref -> ref.startsWith("founder-worker-work-product:")));
                assertTrue(management.outbox().stream()
                        .anyMatch(message -> message.messageType().equals("ObjectiveOutcomeReportReady")));

                assertEquals("actor:" + WORKER_ID, actors.requireActor(WORKER_ID).actorId());
                assertEquals(WorkerActorState.IDLE, actors.requireActor(WORKER_ID).state());
                List<WorkerActorMessage> actorTurns = actors.mailbox(WORKER_ID).stream()
                        .filter(message -> FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY
                                .equals(message.capabilityRef()))
                        .toList();
                assertEquals(3, actorTurns.size());
                assertEquals(List.of(
                                WorkerActorMessage.Status.FAILED,
                                WorkerActorMessage.Status.FAILED,
                                WorkerActorMessage.Status.COMPLETED),
                        actorTurns.stream().map(WorkerActorMessage::status).toList());
                assertTrue(actorTurns.stream().allMatch(message -> objectiveId.equals(message.objectiveId())));
                assertTrue(actorTurns.stream().allMatch(message -> !message.assignmentId().isBlank()));
                assertTrue(actorTurns.stream().allMatch(message -> "founder-worker-cognitive-work".equals(message.stepId())));

                List<ExecutionAttempt> executionAttempts = attempts.all();
                assertEquals(3, executionAttempts.size());
                assertEquals(List.of(
                                ExecutionAttempt.Status.FAILED,
                                ExecutionAttempt.Status.FAILED,
                                ExecutionAttempt.Status.SUCCEEDED),
                        executionAttempts.stream().map(ExecutionAttempt::status).toList());
                assertTrue(executionAttempts.stream().allMatch(attempt -> WORKER_ID.equals(attempt.workerId())));

                assertEquals(2, core.allAssignments().size(),
                        "outer bounded recovery must create a fresh governed Assignment after the first dispatch exhausts runtime recovery");
                assertEquals(List.of(
                                WorkforceCoreService.AssignmentStatus.CANCELLED,
                                WorkforceCoreService.AssignmentStatus.COMPLETED),
                        core.allAssignments().stream().map(WorkforceCoreService.Assignment::status).toList());
                assertTrue(core.allAssignments().stream().allMatch(assignment -> WORKER_ID.equals(assignment.workerId())));

                long localRecoveries = management.history(objectiveId).stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY)
                        .count();
                assertEquals(1, localRecoveries,
                        "recovery must be performed by Workforce itself after bounded runtime recovery is exhausted");
                assertFalse(management.history(objectiveId).stream()
                        .anyMatch(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED));
                assertTrue(coordination.deadLetters().isEmpty());
                assertEquals(DurableWorkGraph.Status.COMPLETED,
                        coordination.activeGraph(objectiveId).orElseThrow().status());
                assertEquals(2, coordination.dispatches().size());
                assertEquals(List.of(DurableDispatch.Status.FAILED, DurableDispatch.Status.SUCCEEDED),
                        coordination.dispatches().stream().map(DurableDispatch::status).toList());
            }
        }
    }

    private static NormalizedRequest request() {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "founder-worker-cognitive-work",
                "WORKER-COMPOSER-ARTIST create an original pop hook concept and deliver the work product",
                WORKER_ID,
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("canonical Worker WORKER-COMPOSER-ARTIST produces the requested cognitive work product"),
                List.of("founder-worker-work-product durable cognitive work product"));
        return new NormalizedRequest(
                "Create an original pop hook concept", WORKER_ID,
                List.of("use the canonical Worker actor", "recover transient provider failures without Human intervention"),
                IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product",
                List.of(),
                List.of("do not claim completion before independent Observation"),
                "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
