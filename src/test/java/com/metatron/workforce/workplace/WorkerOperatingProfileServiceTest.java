package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.memory.PersistentWorkerConversationMemoryStore;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.work.WorkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerOperatingProfileServiceTest {
    @TempDir Path temp;

    @Test
    void composesRealWorkerRelationshipsWithoutPretendingMissingSemanticsExist() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "provenance:test");
        core.admitWorker(GatewayDirectorAppointmentCapability.WORKER_ID, "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron",
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "organization:metatron",
                GatewayDirectorAppointmentCapability.POSITION_REF,
                GatewayDirectorAppointmentCapability.ROLE_REF);
        core.attestCapability(GatewayDirectorAppointmentCapability.WORKER_ID,
                GatewayDirectorAppointmentCapability.CAPABILITY, 1.0, "evidence:appointment");
        core.attestCapability(GatewayDirectorAppointmentCapability.WORKER_ID,
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY, 1.0, "evidence:audit");
        core.attestQualification(GatewayDirectorAppointmentCapability.WORKER_ID,
                "qualification:gateway-director:v1", "evidence:qualification", null);
        core.setAvailability(GatewayDirectorAppointmentCapability.WORKER_ID, true, 1.0);

        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind(GatewayDirectorAppointmentCapability.WORKER_ID,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                Instant.parse("2026-09-09T00:00:00Z"));

        RuntimeRegistry runtimes = new RuntimeRegistry();
        new RuntimeCapacityCoordinator(runtimes).ensureRunning(GatewayDirectorAppointmentCapability.WORKER_ID);

        ManagementAutonomyService management = new ManagementAutonomyService();
        WorkplaceDashboardService dashboard = new WorkplaceDashboardService(core, management, new WorkService());

        InstitutionalRoleGrounding grounding = (role, position, requested, message) ->
                InstitutionalRoleGrounding.Grounding.available(
                        "06_GATEWAY",
                        "## Institutional Mandate\nGateway SHALL continuously provide and maintain controlled ingress and egress.\n"
                                + "## Continuous Ownership Model\nGateway Director owns ASSESS → PLAN → STAFF → OPERATE → IMPROVE → REPORT.\n"
                                + "## Workforce Staffing Model\nEvery position defines mission, reporting relationship, responsibilities, capabilities, authority, resource scope, escalation and success measures.",
                        List.of("institutional-source:test:06_GATEWAY/SOT.md:blob=abc"));

        PersistentWorkerConversationMemoryStore memory = new PersistentWorkerConversationMemoryStore(
                new ObjectMapper(),
                temp.resolve("memory").toString(),
                temp.resolve("legacy").toString());
        memory.append("human-primary", GatewayDirectorAppointmentCapability.WORKER_ID, "workplace",
                "Remember 24/7 Gateway planning.", "Remembered.", "req-1", "runtime-1", List.of());

        WorkerOperatingProfileService service = new WorkerOperatingProfileService(
                core, profiles, runtimes, dashboard,
                List.of(new GatewayDirectorStaffingPolicy()), grounding, memory);

        var profile = service.profile(GatewayDirectorAppointmentCapability.WORKER_ID);

        assertEquals("ROLE-HEAD-OF-GATEWAY", profile.roleRef());
        assertEquals("position:gateway-director", profile.positionRef());
        assertTrue(profile.formation().available());
        assertEquals("policy:founder-gateway-director-appointment:v1", profile.formation().authorityEnvelopeRef());
        assertEquals("qualification:gateway-director:v1", profile.formation().qualificationRef());
        assertTrue(profile.canonicalMandate().available());
        assertTrue(profile.canonicalMandate().content().contains("mission, reporting relationship"));
        assertTrue(profile.runtime().actionRefs().contains("workspace.file.write"));
        assertTrue(profile.runtime().allowedExecutables().contains("git"));
        assertEquals(1, profile.memory().turns());
        assertTrue(profile.liveCognitionInstructions().contains("accountable operating executive"));
        assertTrue(profile.modelGaps().stream().anyMatch(gap -> gap.contains("reporting_relationship: NOT_MODELED")));
        assertFalse(profile.performance().formalEvaluationAvailable());
    }
}
