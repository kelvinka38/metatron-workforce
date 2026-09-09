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
        new RuntimeCapacityCoordinator(registry).ensureRunning("WORKER-1");

        WorkplaceControlRoomService service = new WorkplaceControlRoomService(
                dashboard, core, profiles, registry);

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
}
