package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalWorkerInstitutionalGroundingTest {

    @Test
    void gatewayWorkerReceivesCanonicalInstitutionalGroundingAndBlobEvidence() {
        Fixture fixture = fixture();
        AtomicReference<WorkerIntelligenceService.Request> captured = new AtomicReference<>();
        WorkerIntelligenceService intelligence = request -> {
            captured.set(request);
            return new WorkerIntelligenceService.Response(
                    "worker-cognition-grounded",
                    "Gateway does not own BIOS or Execution semantics.",
                    List.of("worker-intelligence-provider:test"));
        };
        InstitutionalRoleGrounding grounding = (roleRef, positionRef, requestedRole, message) ->
                InstitutionalRoleGrounding.Grounding.available(
                        "06_GATEWAY",
                        """
                        CANONICAL SOURCE
                        repository=kelvinka38/metatron-institution
                        ref=main
                        path=06_GATEWAY/SOT.md
                        blob_sha=380f3407001ee94ea3049984ca08f9ccfb278f24
                        Gateway does not own Worker identity, Workforce, Workplace semantics, Execution semantics or BIOS.
                        FUNCTION != DEDICATED WORKER.
                        """,
                        List.of("institutional-source:kelvinka38/metatron-institution@main:06_GATEWAY/SOT.md:"
                                + "blob=380f3407001ee94ea3049984ca08f9ccfb278f24"));

        CanonicalWorkerConversationService service = new CanonicalWorkerConversationService(
                fixture.core(), fixture.runtimeProfiles(), fixture.runtimeCapacity(), intelligence, grounding);

        WorkerConversationGateway.Reply reply = service.converse(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "Head of Gateway",
                "Audit BIOS for me",
                "");

        assertEquals("Gateway does not own BIOS or Execution semantics.", reply.text());
        assertNotNull(captured.get());
        assertTrue(captured.get().context().contains("CANONICAL INSTITUTIONAL GROUNDING"));
        assertTrue(captured.get().context().contains("path=06_GATEWAY/SOT.md"));
        assertTrue(captured.get().context().contains("blob_sha=380f3407001ee94ea3049984ca08f9ccfb278f24"));
        assertTrue(captured.get().context().contains("FUNCTION != DEDICATED WORKER"));
        assertTrue(captured.get().evidenceReferences().stream()
                .anyMatch(ref -> ref.contains("institutional-source:kelvinka38/metatron-institution@main:06_GATEWAY/SOT.md")));
        assertTrue(reply.evidenceReferences().stream()
                .anyMatch(ref -> ref.contains("blob=380f3407001ee94ea3049984ca08f9ccfb278f24")));
    }

    @Test
    void unavailableCanonicalGroundingFailsClosedBeforeLlmCognition() {
        Fixture fixture = fixture();
        AtomicBoolean cognitionCalled = new AtomicBoolean(false);
        WorkerIntelligenceService intelligence = request -> {
            cognitionCalled.set(true);
            return new WorkerIntelligenceService.Response("never", "never", List.of());
        };
        InstitutionalRoleGrounding grounding = (roleRef, positionRef, requestedRole, message) ->
                InstitutionalRoleGrounding.Grounding.unavailable("canonical-sot-unavailable:06_GATEWAY/SOT.md");

        CanonicalWorkerConversationService service = new CanonicalWorkerConversationService(
                fixture.core(), fixture.runtimeProfiles(), fixture.runtimeCapacity(), intelligence, grounding);

        WorkerConversationGateway.Reply reply = service.converse(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "Head of Gateway",
                "What owns BIOS?",
                "");

        assertFalse(cognitionCalled.get());
        assertTrue(reply.text().contains("Canonical institutional grounding is unavailable"));
        assertTrue(reply.text().contains("I will not answer as Head of Gateway from ungrounded model memory."));
        assertTrue(reply.evidenceReferences().stream()
                .anyMatch(ref -> ref.startsWith("institutional-grounding:unavailable:")));
    }

    @Test
    void fabricatedExecutionClaimFromCognitionIsSuppressedWithoutReceipt() {
        Fixture fixture = fixture();
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "worker-cognition-fabricated",
                "I have reviewed the gateway-orgchart and I am now initiating the BIOS audit.",
                List.of("worker-intelligence-provider:test"));
        InstitutionalRoleGrounding grounding = (roleRef, positionRef, requestedRole, message) ->
                InstitutionalRoleGrounding.Grounding.available(
                        "06_GATEWAY",
                        "path=06_GATEWAY/SOT.md\nGateway does not own BIOS.",
                        List.of("institutional-source:kelvinka38/metatron-institution@main:06_GATEWAY/SOT.md:blob=380f340"));

        CanonicalWorkerConversationService service = new CanonicalWorkerConversationService(
                fixture.core(), fixture.runtimeProfiles(), fixture.runtimeCapacity(), intelligence, grounding);

        WorkerConversationGateway.Reply reply = service.converse(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "Head of Gateway",
                "Audit BIOS for me",
                "");

        assertFalse(reply.text().contains("I have reviewed"));
        assertFalse(reply.text().contains("initiating the BIOS audit"));
        assertTrue(reply.text().contains("không được claim"));
        assertTrue(reply.evidenceReferences().contains("worker-conversation-claim-guard:execution-claim-suppressed"));
    }

    private static Fixture fixture() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:gateway-director-ai", WorkforceCoreService.ParticipantType.AI, "test");
        core.admitWorker(GatewayDirectorAppointmentCapability.WORKER_ID, "participant:gateway-director-ai");
        core.participate("participation:gateway-director:metatron", GatewayDirectorAppointmentCapability.WORKER_ID,
                "organization:metatron", GatewayDirectorAppointmentCapability.POSITION_REF,
                GatewayDirectorAppointmentCapability.ROLE_REF);
        core.attestCapability(GatewayDirectorAppointmentCapability.WORKER_ID,
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY, 1.0, "evidence:test");
        core.setAvailability(GatewayDirectorAppointmentCapability.WORKER_ID, true, 1.0);

        WorkerRuntimeProfileBindingService runtimeProfiles = WorkerRuntimeProfileBindingService.inMemory();
        runtimeProfiles.bind(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GatewayDirectorAppointmentCapability.CAPABILITY,
                Instant.parse("2026-09-09T00:00:00Z"));

        return new Fixture(core, runtimeProfiles, new RuntimeCapacityCoordinator(new RuntimeRegistry()));
    }

    private record Fixture(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimeCapacity) {}
}
