package com.metatron.workforce.core;

import com.metatron.workforce.management.AutonomousStaffingService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalGatewayHeadBootstrapTest {
    @Test
    void governedStaffingProducesTheExistingCanonicalGatewayDirectorIdentity() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService runtimeProfiles = WorkerRuntimeProfileBindingService.inMemory();
        GatewayDirectorAppointmentCapability capability =
                new GatewayDirectorAppointmentCapability(core, runtimeProfiles);
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), runtimeProfiles);

        var first = staffing.ensureStaffed(capability, Instant.parse("2026-09-08T00:00:00Z"));
        var second = staffing.ensureStaffed(capability, Instant.parse("2026-09-08T00:00:01Z"));

        assertEquals(GatewayDirectorAppointmentCapability.WORKER_ID, first.workerId());
        assertEquals(first.workerId(), second.workerId());
        assertNotEquals("worker:head-of-gateway:primary", first.workerId());

        WorkforceCoreService.Worker worker = core.worker(GatewayDirectorAppointmentCapability.WORKER_ID);
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, worker.status());
        assertTrue(core.participations(worker.workerId()).stream().anyMatch(p ->
                p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                        && GatewayDirectorAppointmentCapability.ROLE_REF.equals(p.roleRef())
                        && GatewayDirectorAppointmentCapability.POSITION_REF.equals(p.positionRef())));
        assertTrue(core.capabilities(worker.workerId()).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.capabilities(worker.workerId()).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.availability(worker.workerId()).orElseThrow().available());
        assertDoesNotThrow(() -> runtimeProfiles.requireBinding(worker.workerId()));
    }
}
