package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.core.WorkforceCoreConfiguration;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveManagementConfigurationGovernanceWiringTest {

    @Test
    void liveAutonomyRunnerRequiresExecutionGate() {
        Method runnerFactory = Arrays.stream(LiveManagementConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("autonomousManagementRunner"))
                .findFirst()
                .orElseThrow();

        assertTrue(Arrays.asList(runnerFactory.getParameterTypes()).contains(ExecutionGate.class),
                "production autonomy composition must inject ExecutionGate for mutating capability governance");
    }

    @Test
    void liveManagementServiceRequiresObjectiveCompletionGate() {
        Method serviceFactory = Arrays.stream(LiveManagementConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("managementAutonomyService"))
                .findFirst()
                .orElseThrow();

        assertTrue(Arrays.asList(serviceFactory.getParameterTypes()).contains(ObjectiveCompletionGate.class),
                "production management composition must inject the real ObjectiveCompletionGate");
    }

    @ParameterizedTest
    @EnumSource(value = CompletionPolicy.class, names = {"PR_REQUIRED", "PRODUCTION_REQUIRED"})
    void productionCompositionCannotCompleteReleaseRequiredObjectiveWithoutReleaseEvidence(
            CompletionPolicy policy, @TempDir Path temp) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "METATRON_WORKFORCE_CORE_STATE_PATH", temp.resolve("workforce-core.json").toString(),
                    "METATRON_RELEASE_EVIDENCE_STATE_PATH", temp.resolve("release-evidence.json").toString())));
            context.register(WorkforceCoreConfiguration.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(ObjectiveCompletionGate.class).size(),
                    "production core composition must expose exactly one ObjectiveCompletionGate bean");
            ObjectiveCompletionGate realGate = context.getBean(ObjectiveCompletionGate.class);
            ManagementAutonomyService management = new LiveManagementConfiguration().managementAutonomyService(
                    new InMemoryManagementStateStore(), realGate);
            WorkforceCoreService core = context.getBean(WorkforceCoreService.class);

            String suffix = policy.name().toLowerCase();
            String objectiveId = "objective-" + suffix;
            String assignmentId = "assignment-" + suffix;
            String workerId = "worker-" + suffix;
            String participantId = "participant-" + suffix;
            String participationId = "participation-" + suffix;
            core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI, "admission-" + suffix);
            core.admitWorker(workerId, participantId);
            core.participate(participationId, workerId, "org-metatron", "position-test", "role-test");
            core.setAvailability(workerId, true, 1.0);
            core.assign(assignmentId, objectiveId, workerId, participationId,
                    "authority-test", "authorization-test", "release-required test", policy);

            Instant now = Instant.parse("2026-09-16T00:00:00Z");
            management.acceptHumanObjective(objectiveId, "worker-head", "org-metatron", "release-required test",
                    "human:primary", "request-admission:test", "case-test", "conversation-test",
                    "telegram:update:test-" + suffix, "telegram", request(policy), now);
            ManagementLease lease = management.acquireManagementLease(
                    objectiveId, "runner-test", Duration.ofMinutes(5), now.plusSeconds(1)).orElseThrow();
            management.beginPlanning(objectiveId, "runner-test", lease.token(), now.plusSeconds(2));
            management.recordPlan(objectiveId, "runner-test", lease.token(), List.of(step()), now.plusSeconds(3));
            management.beginExecution(objectiveId, "runner-test", lease.token(), now.plusSeconds(4));
            management.recordStepCompleted(objectiveId, "runner-test", lease.token(), "step-1",
                    List.of("execution-evidence:" + suffix), now.plusSeconds(5));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> management.completeAutonomousObjective(
                            objectiveId, "runner-test", lease.token(), now.plusSeconds(6)));

            assertTrue(failure.getMessage().startsWith("objective-completion-evidence-required:" + policy));
            assertEquals(ManagementObjective.Status.EXECUTING, management.get(objectiveId).status(),
                    "release-required Objective must remain nonterminal until real release evidence exists");
            assertEquals(AutonomousObjectiveWork.Status.EXECUTING,
                    management.findAutonomousWork(objectiveId).orElseThrow().status());
        }
    }

    private static NormalizedRequest request(CompletionPolicy policy) {
        return new NormalizedRequest(
                "release-required objective", "metatron-workforce", List.of(), IntelligenceDepth.ANALYZE,
                "produce governed result", List.of(), List.of(), "current", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(), false, null, LlmProvider.OPENAI, "").withCompletionPolicy(policy);
    }

    private static ExecutionWorkSpec step() {
        return new ExecutionWorkSpec("step-1", "execute governed work", "metatron-workforce", "test.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("governed work completes"), List.of("execution evidence exists"));
    }
}
