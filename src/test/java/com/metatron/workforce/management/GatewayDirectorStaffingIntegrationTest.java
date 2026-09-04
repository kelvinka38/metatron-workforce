package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayDirectorStaffingIntegrationTest {
    @Test
    void founderAppointmentFormsPersistentGatewayDirectorWithRoleCapabilityAndRuntime() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        GatewayDirectorAppointmentCapability delegate =
                new GatewayDirectorAppointmentCapability(core, profiles);
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), profiles);
        GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                delegate,
                core,
                new ExecutionAdmissionService(),
                Clock.fixed(Instant.parse("2026-09-04T13:00:00Z"), ZoneOffset.UTC),
                staffing);

        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "appoint-gateway-director",
                "Form and appoint the canonical Gateway Director / Head of Gateway Worker through governed Workforce staffing",
                GatewayDirectorAppointmentCapability.ROLE_REF,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of(
                        "Gateway Director Worker is ACTIVE with ROLE-HEAD-OF-GATEWAY participation",
                        "Gateway Director has the approved gateway.audit.read capability",
                        "Gateway Director has a durable usable runtime/tool profile binding"),
                List.of("staffing evidence", "role evidence", "runtime binding evidence"));

        AutonomousExecutionCapability.CapabilityResult result = governed.execute(
                new AutonomousExecutionCapability.CapabilityRequest(
                        "human-primary", "organization:metatron", "objective:gateway-director", work));

        assertTrue(result.success());
        assertEquals(GatewayDirectorAppointmentCapability.WORKER_ID, result.workerId());
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE,
                core.worker(GatewayDirectorAppointmentCapability.WORKER_ID).status());
        assertTrue(core.participations(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(p ->
                p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                        && GatewayDirectorAppointmentCapability.ROLE_REF.equals(p.roleRef())
                        && GatewayDirectorAppointmentCapability.POSITION_REF.equals(p.positionRef())));
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY.equals(c.capabilityRef())));
        assertEquals(WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                profiles.requireBinding(GatewayDirectorAppointmentCapability.WORKER_ID).profile().profileRef());
        assertTrue(core.allAssignments().stream().anyMatch(a ->
                a.workerId().equals(GatewayDirectorAppointmentCapability.WORKER_ID)
                        && a.status() == WorkforceCoreService.AssignmentStatus.COMPLETED));
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.contains("staffing:policy=" + GatewayDirectorAppointmentCapability.CAPABILITY)));
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.contains("additional-capabilities=[gateway.audit.read]")));
    }
}
