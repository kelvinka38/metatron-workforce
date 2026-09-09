package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.observation.GatewayDirectorAppointmentObservationVerifier;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;
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
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), profiles, constitution);
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
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.OPERATIONAL_MANAGEMENT_CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.RELIABILITY_MANAGEMENT_CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.CAPACITY_COST_MANAGEMENT_CAPABILITY.equals(c.capabilityRef())));
        assertTrue(core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream().anyMatch(c ->
                GatewayDirectorAppointmentCapability.INCIDENT_RECOVERY_CAPABILITY.equals(c.capabilityRef())));
        var constitutionContext = constitution.contextFor(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "participation:gateway-director:metatron");
        assertTrue(constitutionContext.contract().mission().contains("controlled institutional boundary"));
        assertFalse(constitutionContext.contract().reportingLines().isEmpty());
        assertFalse(constitutionContext.contract().resourceScopes().isEmpty());
        assertFalse(constitutionContext.contract().escalationRoutes().isEmpty());
        assertFalse(constitutionContext.contract().successMeasures().isEmpty());
        assertEquals("CONTINUOUS_ACCOUNTABILITY_WITH_DEMAND_DRIVEN_BOUNDED_EXECUTION",
                constitutionContext.contract().operatingCoverage());
        assertEquals(WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                profiles.requireBinding(GatewayDirectorAppointmentCapability.WORKER_ID).profile().profileRef());
        assertTrue(core.allAssignments().stream().anyMatch(a ->
                a.workerId().equals(GatewayDirectorAppointmentCapability.WORKER_ID)
                        && a.status() == WorkforceCoreService.AssignmentStatus.COMPLETED));
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.contains("staffing:policy=" + GatewayDirectorAppointmentCapability.CAPABILITY)));
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.contains("position-contract-bound:position-contract:position:gateway-director:v1")));
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.contains("gateway.operational.management")));

        GatewayDirectorAppointmentObservationVerifier verifier =
                new GatewayDirectorAppointmentObservationVerifier(core, profiles);
        ObservationRequirement requirement = new ObservationRequirement(
                "requirement:gateway-director",
                "objective:gateway-director",
                "appoint-gateway-director",
                "criterion:gateway-director-role",
                GatewayDirectorAppointmentCapability.ROLE_REF,
                "Gateway Director Worker is ACTIVE with ROLE-HEAD-OF-GATEWAY participation",
                List.of(GatewayDirectorAppointmentObservationVerifier.MARKER),
                Instant.parse("2026-09-04T13:00:01Z"));
        ObservationReport report = verifier.observe(
                requirement, result.evidenceReferences(), Instant.parse("2026-09-04T13:00:02Z")).orElseThrow();

        assertEquals(ObservationReport.CriterionResult.PASS, report.criterionResult());
        assertEquals("independent-workforce-core-and-runtime-profile-read", report.method());
    }
}
