package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.work.WorkService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorkplaceControlRoomServiceTest {
    @Test
    void projectsWorkersAndRuntimeAreComposedFromCanonicalSources() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("P-1", WorkforceCoreService.ParticipantType.AI, "prov");
        core.admitWorker("WORKER-1", "P-1");
        core.participate("PART-1", "WORKER-1", "organization:metatron",
                "position:general-engineering-executor", "role:general-code-and-runtime-worker");
        core.attestCapability("WORKER-1", "execution.general.workspace", 1.0, "e-cap");
        core.setAvailability("WORKER-1", true, 2.0);
        core.assign("A-1", "OBJ-1", "WORKER-1", "PART-1", "AUTH", "AUTHZ", "Own the repair");

        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptObjective("OBJ-1", "WORKER-1", "project:metatron-control-room",
                "Build the Workplace Control Room", Instant.now());

        WorkplaceDashboardService dashboard = new WorkplaceDashboardService(core, management, new WorkService());

        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind("WORKER-1", WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "execution.general.workspace", Instant.now());
        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(registry);
        runtimeCapacity.ensureRunning("WORKER-1");

        WorkplaceControlRoomService service = new WorkplaceControlRoomService(
                dashboard, core, profiles, registry, runtimeCapacity);

        var snapshot = service.snapshot();
        assertEquals(1, snapshot.workers().size());
        assertEquals("project:metatron-control-room", snapshot.projects().getFirst().projectId());
        assertEquals(1, snapshot.projects().getFirst().objectives());

        var detail = service.worker("WORKER-1");
        assertEquals("role:general-code-and-runtime-worker", detail.primaryRole());
        assertEquals("RUNNING", detail.runtimeState());
        assertEquals(1, detail.assignments().size());
        assertEquals("OBJ-1", detail.assignments().getFirst().objectiveId());
        assertFalse(detail.runtimeActions().isEmpty());
    }


    @Test
    void historicalGatewayHeadIsNotListedAndChatResolvesToCanonicalRunningWorker() {
        WorkforceCoreService core = new WorkforceCoreService();

        core.recognizeParticipant("P-CANON", WorkforceCoreService.ParticipantType.AI, "canon");
        core.admitWorker("WORKER-GATEWAY-DIRECTOR", "P-CANON");
        core.participate("PART-CANON", "WORKER-GATEWAY-DIRECTOR", "organization:metatron",
                "position:gateway-director", "ROLE-HEAD-OF-GATEWAY");
        core.attestCapability("WORKER-GATEWAY-DIRECTOR", "gateway.audit.read", 1.0, "evidence:canon");
        core.setAvailability("WORKER-GATEWAY-DIRECTOR", true, 1.0);

        core.recognizeParticipant("P-LEGACY", WorkforceCoreService.ParticipantType.AI, "legacy");
        core.admitWorker("WORKER-GATEWAY-HEAD-01", "P-LEGACY");
        core.participate("PART-LEGACY", "WORKER-GATEWAY-HEAD-01", "organization:metatron",
                "position:gateway-head-legacy", "ROLE-HEAD-OF-GATEWAY");
        core.setParticipationStatus("PART-LEGACY", WorkforceCoreService.ParticipationStatus.ENDED);
        core.setWorkerStatus("WORKER-GATEWAY-HEAD-01", WorkforceCoreService.WorkerStatus.RETIRED);

        ManagementAutonomyService management = new ManagementAutonomyService();
        WorkplaceDashboardService dashboard = new WorkplaceDashboardService(core, management, new WorkService());

        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind("WORKER-GATEWAY-DIRECTOR", WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "workforce.staffing.gateway-director", Instant.now());

        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeCapacityCoordinator runtimeCapacity = new RuntimeCapacityCoordinator(registry);
        WorkplaceControlRoomService service = new WorkplaceControlRoomService(
                dashboard, core, profiles, registry, runtimeCapacity);

        var snapshot = service.snapshot();
        assertEquals(1, snapshot.workers().size());
        assertEquals("WORKER-GATEWAY-DIRECTOR", snapshot.workers().getFirst().workerId());

        var legacy = service.worker("WORKER-GATEWAY-HEAD-01");
        assertFalse(legacy.chatAvailable());
        assertEquals("WORKER-GATEWAY-DIRECTOR", legacy.canonicalReplacementWorkerId());

        var prepared = service.prepareConversationWorker("WORKER-GATEWAY-HEAD-01");
        assertEquals("WORKER-GATEWAY-DIRECTOR", prepared.workerId());
        assertTrue(prepared.chatAvailable());
        assertEquals("RUNNING", prepared.runtimeState());
    }
}
