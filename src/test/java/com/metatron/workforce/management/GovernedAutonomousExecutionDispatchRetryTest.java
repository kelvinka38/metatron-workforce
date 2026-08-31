package com.metatron.workforce.management;

import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.FileExecutionAttemptStore;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernedAutonomousExecutionDispatchRetryTest {
    private static final Instant T0 = Instant.parse("2026-08-31T15:30:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @TempDir Path temp;

    @Test
    void boundedDispatchRetryGetsFreshAllocationWhileEffectIdempotencyRemainsStable() {
        WorkforceCoreService core = seededCore(new WorkforceCoreService());

        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability delegate = successfulAfterOneFailure(effects);

        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate, core, new ExecutionAdmissionService(), CLOCK, Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> governed.execute(request(1)));
        var second = governed.execute(request(2));

        assertTrue(second.success());
        assertEquals(2, effects.get());
        assertEquals(2, core.allAssignments().size());
        assertEquals(2, core.allCapacityReservations().size());
        assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED, core.allAssignments().get(0).status());
        assertEquals(WorkforceCoreService.AssignmentStatus.COMPLETED, core.allAssignments().get(1).status());
        assertTrue(core.allCapacityReservations().stream()
                .allMatch(r -> r.status() == WorkforceCoreService.ReservationStatus.RELEASED));
        assertEquals(1.0, core.remainingCapacity("worker-a"), 0.000001);
    }

    @Test
    void restartReclaimsCapacityOnlyAfterOwningExecutionLeaseIsTerminal() {
        Path corePath = temp.resolve("core.json");
        Path attemptPath = temp.resolve("attempts.json");
        WorkforceCoreService originalCore = seededCore(new WorkforceCoreService(new FileWorkforceCoreStateStore(corePath)));
        ExecutionAttemptService originalAttempts = new ExecutionAttemptService(new FileExecutionAttemptStore(attemptPath));
        seedInterruptedAllocation(originalCore, originalAttempts, T0);

        Clock restartClock = Clock.fixed(T0.plusSeconds(31), ZoneOffset.UTC);
        WorkforceCoreService restartedCore = new WorkforceCoreService(new FileWorkforceCoreStateStore(corePath));
        ExecutionAttemptService restartedAttempts = new ExecutionAttemptService(new FileExecutionAttemptStore(attemptPath));
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(
                new RuntimeRegistry(new FileRuntimePersistenceStore(temp.resolve("runtimes-expired"))));
        AtomicInteger effects = new AtomicInteger();
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                alwaysSuccessful(effects), restartedCore, new ExecutionAdmissionService(), restartClock,
                Duration.ZERO, null, restartedAttempts, runtimeCapacity);

        var result = governed.execute(request(2));

        assertTrue(result.success());
        assertEquals(1, effects.get());
        assertEquals(2, restartedCore.allAssignments().size());
        assertEquals(WorkforceCoreService.AssignmentStatus.CANCELLED,
                restartedCore.allAssignments().stream().filter(a -> a.assignmentId().equals("assignment:orphaned"))
                        .findFirst().orElseThrow().status());
        assertTrue(restartedCore.allCapacityReservations().stream()
                .filter(r -> r.reservationId().equals("capacity-reservation:orphaned"))
                .allMatch(r -> r.status() == WorkforceCoreService.ReservationStatus.RELEASED));
        assertEquals(ExecutionAttempt.Status.ABANDONED,
                restartedAttempts.all().stream().filter(a -> a.assignmentRef().equals("assignment:orphaned"))
                        .findFirst().orElseThrow().status());
        assertEquals(1.0, restartedCore.remainingCapacity("worker-a"), 0.000001);
        assertTrue(result.evidenceReferences().stream()
                .anyMatch(e -> e.contains("capacity-reconciled:assignment=assignment:orphaned")));
    }

    @Test
    void restartDoesNotReclaimCapacityWhileOwningExecutionLeaseIsLive() {
        Path corePath = temp.resolve("core-live.json");
        Path attemptPath = temp.resolve("attempts-live.json");
        WorkforceCoreService originalCore = seededCore(new WorkforceCoreService(new FileWorkforceCoreStateStore(corePath)));
        ExecutionAttemptService originalAttempts = new ExecutionAttemptService(new FileExecutionAttemptStore(attemptPath));
        seedInterruptedAllocation(originalCore, originalAttempts, T0);

        Clock restartClock = Clock.fixed(T0.plusSeconds(10), ZoneOffset.UTC);
        WorkforceCoreService restartedCore = new WorkforceCoreService(new FileWorkforceCoreStateStore(corePath));
        ExecutionAttemptService restartedAttempts = new ExecutionAttemptService(new FileExecutionAttemptStore(attemptPath));
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(
                new RuntimeRegistry(new FileRuntimePersistenceStore(temp.resolve("runtimes-live"))));
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                alwaysSuccessful(new AtomicInteger()), restartedCore, new ExecutionAdmissionService(), restartClock,
                Duration.ZERO, null, restartedAttempts, runtimeCapacity);

        IllegalStateException blocked = assertThrows(IllegalStateException.class, () -> governed.execute(request(2)));

        assertTrue(blocked.getMessage().contains("capacity-unavailable:repository.audit.read"));
        assertEquals(WorkforceCoreService.AssignmentStatus.ACTIVE,
                restartedCore.allAssignments().stream().filter(a -> a.assignmentId().equals("assignment:orphaned"))
                        .findFirst().orElseThrow().status());
        assertEquals(WorkforceCoreService.ReservationStatus.ACTIVE,
                restartedCore.allCapacityReservations().stream()
                        .filter(r -> r.reservationId().equals("capacity-reservation:orphaned"))
                        .findFirst().orElseThrow().status());
        assertTrue(restartedAttempts.all().stream()
                .filter(a -> a.assignmentRef().equals("assignment:orphaned"))
                .allMatch(a -> !a.terminal()));
        assertEquals(0.0, restartedCore.remainingCapacity("worker-a"), 0.000001);
    }

    private static void seedInterruptedAllocation(WorkforceCoreService core, ExecutionAttemptService attempts, Instant at) {
        core.reserveCapacity("capacity-reservation:orphaned", "assignment:orphaned", "objective-a", "worker-a", 1.0);
        WorkforceCoreService.Assignment assignment = core.assignReserved(
                "capacity-reservation:orphaned", "participation-a", "authority:test", "authorization:test", "Audit repository");
        attempts.begin("objective-a:graph:1:step:step-a:attempt:1", "objective-a", "step-a", "worker-a",
                assignment.assignmentId(), assignment.authorizationRef(), "runtime:orphaned", 1,
                Duration.ofSeconds(30), at);
    }

    private static AutonomousExecutionCapability successfulAfterOneFailure(AtomicInteger effects) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "repository.audit.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return "worker-a".equals(workerId); }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                assertTrue(request.allocated());
                assertEquals("objective-a:work-step:step-a", request.idempotencyKey());
                if (effects.incrementAndGet() == 1) throw new IllegalStateException("transient-provider-failure");
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-a", List.of("evidence:work-a"), "PASS");
            }
        };
    }

    private static AutonomousExecutionCapability alwaysSuccessful(AtomicInteger effects) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "repository.audit.read"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public boolean supportsWorker(String workerId) { return "worker-a".equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work-a", List.of("evidence:work-a"), "PASS");
            }
        };
    }

    private static WorkforceCoreService seededCore(WorkforceCoreService core) {
        core.recognizeParticipant("participant-a", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker("worker-a", "participant-a");
        core.participate("participation-a", "worker-a", "org-metatron", "position", "role");
        core.attestCapability("worker-a", "repository.audit.read", 1.0, "evidence:capability");
        core.setAvailability("worker-a", true, 1.0);
        return core;
    }

    private static AutonomousExecutionCapability.CapabilityRequest request(int dispatchAttempt) {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-a", "Audit repository", "kelvinka38/metatron-workforce", "repository.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);
        return new AutonomousExecutionCapability.CapabilityRequest(
                "human:primary", "org-metatron", "objective-a", step)
                .withDispatch("objective-a:graph:1:step:step-a:attempt:" + dispatchAttempt, dispatchAttempt);
    }
}
