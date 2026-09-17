package com.metatron.workforce.operating;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import com.metatron.workforce.phase5.WorkSchedule;
import com.metatron.workforce.phase5.WorkScheduleService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkerConstitutionRuntimeMaterializerTest {
    @TempDir Path temp;

    @Test
    void materializesAndDurablyRehydratesFullSotWorkerRuntimeConstitution() {
        String workerId = GatewayDirectorAppointmentCapability.WORKER_ID;
        String participationId = "participation:gateway-director:metatron";
        Instant now = Instant.parse("2026-09-10T00:00:00Z");

        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant(
                "participant:gateway-director-ai",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:gateway-director:test");
        core.admitWorker(workerId, "participant:gateway-director-ai");
        core.participate(
                participationId,
                workerId,
                "organization:metatron",
                GatewayDirectorAppointmentCapability.POSITION_REF,
                GatewayDirectorAppointmentCapability.ROLE_REF);
        core.attestCapability(
                workerId,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                1.0,
                "evidence:gateway-director-appointment:test");
        core.attestCapability(
                workerId,
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY,
                1.0,
                "evidence:gateway-audit:test");
        core.attestQualification(
                workerId,
                "qualification:gateway-director:v1",
                "evidence:qualification:test",
                null);
        core.setAvailability(workerId, true, 2.0);

        WorkerConstitutionService constitution =
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(
                        temp.resolve("standing.json")));
        GatewayDirectorStaffingPolicy policy = new GatewayDirectorStaffingPolicy();
        constitution.ensureConstitution(policy, now.minusSeconds(60));

        WorkerRuntimeProfileBindingService profiles =
                new WorkerRuntimeProfileBindingService(temp.resolve("profiles.tsv"));
        profiles.bind(
                workerId,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                now.minusSeconds(60));

        RuntimeRegistry runtimes = new RuntimeRegistry();
        var runtime = new RuntimeCapacityCoordinator(runtimes).ensureRunning(workerId);

        String reservationId = "reservation:test";
        String assignmentId = "assignment:test";
        core.reserveCapacity(reservationId, assignmentId, "objective:test", workerId, 1.0);
        core.assignReserved(
                reservationId,
                participationId,
                GatewayDirectorAppointmentCapability.AUTHORITY_REFERENCE,
                GatewayDirectorAppointmentCapability.AUTHORIZATION_REFERENCE,
                "Operate Gateway test objective");

        WorkScheduleService schedules = new WorkScheduleService();
        schedules.schedule(new WorkSchedule(
                "schedule:test",
                assignmentId,
                workerId,
                now.minusSeconds(30),
                now.plusSeconds(300),
                1.0,
                WorkSchedule.Status.ACTIVE,
                "evidence:schedule:test"), 2.0);

        ExecutionAttemptService attempts = new ExecutionAttemptService();
        attempts.begin(
                "dispatch:test",
                "objective:test",
                "step:test",
                workerId,
                assignmentId,
                GatewayDirectorAppointmentCapability.AUTHORIZATION_REFERENCE,
                runtime.runtimeId(),
                1,
                Duration.ofMinutes(5),
                now);

        Path runtimeState = temp.resolve("worker-constitution-runtime.json");
        WorkerConstitutionRuntimeMaterializer materializer =
                new WorkerConstitutionRuntimeMaterializer(
                        core,
                        constitution,
                        schedules,
                        profiles,
                        runtimes,
                        attempts,
                        new FileWorkerConstitutionRuntimeStateStore(runtimeState));

        var snapshot = materializer.materialize(workerId, participationId, now.plusSeconds(1));

        assertEquals(workerId, snapshot.worker().workerId());
        assertEquals("participant:gateway-director-ai", snapshot.participant().participantId());
        assertEquals(participationId, snapshot.participation().participationId());
        assertEquals(GatewayDirectorAppointmentCapability.POSITION_REF, snapshot.positionContract().positionRef());
        assertTrue(snapshot.positionContract().mission().contains("Continuously own Gateway"));
        assertTrue(snapshot.capabilities().stream()
                .anyMatch(value -> value.capabilityRef().equals(
                        GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY)));
        assertTrue(snapshot.qualifications().stream()
                .anyMatch(value -> value.qualificationRef().equals("qualification:gateway-director:v1")));
        assertTrue(snapshot.capacity().available());
        assertEquals(2.0, snapshot.capacity().configuredCapacity(), 0.0001);
        assertEquals(1.0, snapshot.capacity().reservedCapacity(), 0.0001);
        assertEquals(1.0, snapshot.capacity().remainingCapacity(), 0.0001);
        assertEquals(1, snapshot.capacityReservations().size());
        assertEquals(1, snapshot.assignments().size());
        assertEquals(GatewayDirectorAppointmentCapability.AUTHORITY_REFERENCE,
                snapshot.assignments().getFirst().authorityRef());
        assertEquals(GatewayDirectorAppointmentCapability.AUTHORIZATION_REFERENCE,
                snapshot.assignments().getFirst().authorizationRef());
        assertEquals(java.util.List.of("objective:test"), snapshot.objectiveReferences());
        assertEquals(1, snapshot.schedules().size());
        assertEquals(runtime.runtimeId(), snapshot.runtime().runtimeId());
        assertEquals("RUNNING", snapshot.runtime().runtimeState());
        assertEquals(1, snapshot.executionAttribution().size());
        assertEquals(assignmentId, snapshot.executionAttribution().getFirst().assignmentRef());
        assertTrue(snapshot.reputationClaims().isEmpty());
        assertTrue(snapshot.renderedContext().contains("MATERIALIZED WORKER CONSTITUTION — RUNTIME"));
        assertTrue(snapshot.renderedContext().contains("ACTUAL CAPABILITY RELATIONSHIPS"));
        assertTrue(snapshot.renderedContext().contains("CURRENT / HISTORICAL ASSIGNMENT + AUTHORITY + AUTHORIZATION RELATIONSHIPS"));
        assertTrue(snapshot.renderedContext().contains("EXECUTION ATTRIBUTION"));

        WorkerConstitutionRuntimeMaterializer restarted =
                new WorkerConstitutionRuntimeMaterializer(
                        core,
                        constitution,
                        schedules,
                        profiles,
                        runtimes,
                        attempts,
                        new FileWorkerConstitutionRuntimeStateStore(runtimeState));
        var durable = restarted.current(workerId, participationId).orElseThrow();
        assertEquals(snapshot.snapshotId(), durable.snapshotId());
        assertEquals(snapshot.assignments(), durable.assignments());
        assertEquals(snapshot.executionAttribution(), durable.executionAttribution());
        assertEquals(snapshot.positionContract().mission(), durable.positionContract().mission());

        // Regression: durable institutional history may grow without being dumped into cognition.
        // The current Assignment remains fully represented while unrelated historical Assignment/
        // reservation relationships stay in the durable source snapshot only.
        core.setAvailability(workerId, true, 500.0);
        for (int i = 0; i < 150; i++) {
            String historicalAssignment = "assignment:history:" + i;
            String historicalReservation = "reservation:history:" + i;
            core.reserveCapacity(historicalReservation, historicalAssignment, "objective:history:" + i, workerId, 1.0);
            core.assignReserved(
                    historicalReservation,
                    participationId,
                    GatewayDirectorAppointmentCapability.AUTHORITY_REFERENCE,
                    GatewayDirectorAppointmentCapability.AUTHORIZATION_REFERENCE,
                    "Historical governed assignment " + i);
        }

        var expanded = materializer.materializeForAssignment(workerId, assignmentId, now.plusSeconds(2));
        String cognitiveContext = WorkerCognitionContextProjector.forAssignment(expanded, assignmentId);

        assertEquals(151, expanded.assignments().size());
        assertEquals(151, expanded.capacityReservations().size());
        assertTrue(expanded.renderedContext().contains("assignment:history:149"));
        assertTrue(cognitiveContext.contains("assignment=" + assignmentId));
        assertTrue(cognitiveContext.contains("objective=objective:test"));
        assertFalse(cognitiveContext.contains("assignment:history:149"));
        assertFalse(cognitiveContext.contains("objective:history:149"));
        assertTrue(cognitiveContext.length() <= WorkerCognitionContextProjector.MAX_CONTEXT_CHARS);
        assertTrue(cognitiveContext.length() < expanded.renderedContext().length());
    }
}
