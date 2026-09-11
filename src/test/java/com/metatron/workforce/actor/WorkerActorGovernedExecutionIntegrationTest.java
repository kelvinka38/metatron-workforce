package com.metatron.workforce.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.management.AutonomousExecutionCapability;
import com.metatron.workforce.management.FounderDefinedCognitiveWorkCapability;
import com.metatron.workforce.management.FounderWorkerWorkProductStore;
import com.metatron.workforce.management.GovernedAutonomousExecutionCapability;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerActorGovernedExecutionIntegrationTest {
    @Test
    void realGovernedAssignmentCognitionRunsInsideAssignedWorkersActorLane(@TempDir Path temp) {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        try (WorkerActorRuntime actors = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4)) {
            RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry(), actors);
            FounderDefinedWorkerFormationService formation = new FounderDefinedWorkerFormationService(
                    core, profiles, constitution, runtimes);
            formation.form("composer/artist", "Founder-defined composer", Instant.parse("2026-09-11T10:00:00Z"));

            WorkerIntelligenceService provider = request -> {
                assertEquals("WORKER-COMPOSER-ARTIST", request.requester());
                return new WorkerIntelligenceService.Response(
                        "provider:composer", "Original work product", List.of("provider:test"));
            };
            WorkerIntelligenceService scoped = new ActorScopedWorkerIntelligenceService(provider, actors);
            FounderWorkerWorkProductStore products = new FounderWorkerWorkProductStore(
                    new ObjectMapper(), temp.resolve("products").toString());
            FounderDefinedCognitiveWorkCapability delegate = new FounderDefinedCognitiveWorkCapability(
                    core, profiles, constitution, scoped, products);
            GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                    delegate, core, new ExecutionAdmissionService(),
                    Clock.fixed(Instant.parse("2026-09-11T10:00:00Z"), ZoneOffset.UTC),
                    null, new ExecutionAttemptService(), runtimes);

            ExecutionWorkSpec work = new ExecutionWorkSpec(
                    "compose-step",
                    "WORKER-COMPOSER-ARTIST create an original concept",
                    "WORKER-COMPOSER-ARTIST",
                    FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
                    List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                    List.of("work product exists"),
                    List.of("founder-worker-work-product durable cognitive work product"));

            AutonomousExecutionCapability.CapabilityResult result = governed.execute(
                    new AutonomousExecutionCapability.CapabilityRequest(
                            "human-primary", "metatron", "objective:actor:composer", work));

            assertTrue(result.success());
            assertEquals("WORKER-COMPOSER-ARTIST", result.workerId());
            assertEquals(WorkerActorState.IDLE, actors.requireActor("WORKER-COMPOSER-ARTIST").state());
            assertTrue(actors.mailbox("WORKER-COMPOSER-ARTIST").stream().anyMatch(message ->
                    message.status() == WorkerActorMessage.Status.COMPLETED
                            && FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(message.capabilityRef())
                            && "objective:actor:composer".equals(message.objectiveId())
                            && message.assignmentId().startsWith("assignment:")));
        }
    }
}
