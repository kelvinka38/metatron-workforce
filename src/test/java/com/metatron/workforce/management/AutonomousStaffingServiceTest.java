package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
