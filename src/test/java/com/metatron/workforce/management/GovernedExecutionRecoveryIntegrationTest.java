package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.FileExecutionAttemptStore;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.RuntimeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedExecutionRecoveryIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-31T03:30:00Z"), ZoneOffset.UTC);

    @TempDir Path temp;

    @Test
    void readOnlyDispatchRecoversOnReplacementRuntimeWithoutReplacingWorkerOrAssignment() {
        WorkforceCoreService core = seededCore();
        ExecutionAttemptService attempts = new ExecutionAttemptService(
                new FileExecutionAttemptStore(temp.resolve("attempts.json")));
        RuntimeRegistry runtimes = new RuntimeRegistry(new FileRuntimePersistenceStore(temp.resolve("runtimes")));
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(runtimes);
        AtomicInteger effects = new AtomicInteger();

        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return workerId.equals("worker-a"); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                assertTrue(request.allocated());
                if (effects.incrementAndGet() == 1) throw new IllegalStateException("provider-timeout");
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work:recovered", List.of("evidence:recovered"), "PASS");
            }
        };

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, null, attempts, runtimeCapacity);
        var request = new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", "objective-a",
                new ExecutionWorkSpec("step-a", "Read repository", "repo", "test.read", List.of(),
                        ExecutionWorkSpec.Consequence.READ_ONLY))
                .withDispatch("objective-a:graph:1:step:step-a:attempt:1", 1);

        var result = governed.execute(request);

        assertTrue(result.success());
        assertEquals(2, effects.get());
        assertEquals("worker-a", result.workerId());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().getFirst().status());
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);

        List<ExecutionAttempt> recorded = attempts.all();
        assertEquals(2, recorded.size());
        ExecutionAttempt first = recorded.get(0);
        ExecutionAttempt second = recorded.get(1);
        assertEquals(ExecutionAttempt.Status.FAILED, first.status());
        assertEquals(ExecutionAttempt.Status.SUCCEEDED, second.status());
        assertNotEquals(first.runtimeId(), second.runtimeId());
        assertEquals(first.workerId(), second.workerId());
        assertEquals(first.assignmentRef(), second.assignmentRef());
        assertTrue(second.fencingToken() > first.fencingToken());
        assertEquals(RuntimeState.FAILED, runtimes.get(first.runtimeId()).state());
        assertEquals(RuntimeState.TERMINATED, runtimes.get(second.runtimeId()).state());
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.contains("runtime-replaced:worker=worker-a")));
        assertTrue(result.evidenceReferences().stream().anyMatch(e -> e.contains("dispatch=objective-a:graph:1:step:step-a:attempt:1")));
    }

    @Test
    void mutatingDispatchDoesNotBlindlyRetryUnknownFailure() {
        WorkforceCoreService core = seededCore();
        ExecutionAttemptService attempts = new ExecutionAttemptService(
                new FileExecutionAttemptStore(temp.resolve("mutating-attempts.json")));
        RuntimeRegistry runtimes = new RuntimeRegistry(new FileRuntimePersistenceStore(temp.resolve("mutating-runtimes")));
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(runtimes);
        AtomicInteger effects = new AtomicInteger();

        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return workerId.equals("worker-a"); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                throw new IllegalStateException("unknown-mutation-effect");
            }
        };

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, null, attempts, runtimeCapacity);
        var request = new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", "objective-mutating",
                new ExecutionWorkSpec("step-m", "Mutate repository", "repo", "test.read", List.of(),
                        ExecutionWorkSpec.Consequence.MUTATING))
                .withDispatch("objective-mutating:graph:1:step:step-m:attempt:1", 1);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> governed.execute(request));
        assertEquals(1, effects.get());
        assertEquals(1, attempts.all().size());
        assertEquals(ExecutionAttempt.Status.FAILED, attempts.all().getFirst().status());
        assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED, core.allAssignments().getFirst().status());
    }

    private static WorkforceCoreService seededCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-a");
        core.participate("participation-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", "test.read", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);
        return core;
    }
}
