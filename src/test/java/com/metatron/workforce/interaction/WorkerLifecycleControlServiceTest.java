package com.metatron.workforce.interaction;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AutonomousStaffingService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerLifecycleControlServiceTest {
    @Test
    void createsCanonicalGatewayWorkerDirectlyAndReusesSameLiveRuntime() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        GatewayDirectorAppointmentCapability capability = new GatewayDirectorAppointmentCapability(core, profiles);
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), profiles);
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        WorkerLifecycleControlService service = new WorkerLifecycleControlService(
                staffing, capability, core, profiles, runtimes);

        assertTrue(service.supports("Create worker Head of Gateway"));
        assertTrue(service.supports("Tạo worker Head of Gateway"));
        assertFalse(service.supports("Audit Gateway routing"));

        String first = service.handle("human-primary", "Create worker Head of Gateway").orElseThrow();
        assertTrue(first.startsWith("🧰 **METATRON · WORKER READY**"));
        assertTrue(first.contains("worker_id=`WORKER-GATEWAY-DIRECTOR`"));
        assertTrue(first.contains("runtime_state=RUNNING"));
        assertTrue(first.contains("worker_status=ACTIVE"));
        assertTrue(first.contains("formation=CREATED"));
        assertFalse(first.contains("objective_id="));
        assertFalse(first.contains("queue_item="));
        String runtimeId = extract(first, "runtime_id=`", "`");
        assertFalse(runtimeId.isBlank());

        String second = service.handle("human-primary", "Provision worker Gateway Director").orElseThrow();
        assertTrue(second.contains("formation=REUSED"));
        assertEquals(runtimeId, extract(second, "runtime_id=`", "`"));
        assertEquals(1, core.allWorkers().stream()
                .filter(w -> "WORKER-GATEWAY-DIRECTOR".equals(w.workerId())).count());
    }

    @Test
    void rejectsNonFounderFormation() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        GatewayDirectorAppointmentCapability capability = new GatewayDirectorAppointmentCapability(core, profiles);
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), profiles);
        WorkerLifecycleControlService service = new WorkerLifecycleControlService(
                staffing, capability, core, profiles,
                new RuntimeCapacityCoordinator(new RuntimeRegistry()));

        assertThrows(SecurityException.class, () ->
                service.handle("human-other", "Create worker Head of Gateway"));
        assertTrue(core.allWorkers().isEmpty());
    }

    private static String extract(String value, String prefix, String suffix) {
        int start = value.indexOf(prefix);
        if (start < 0) return "";
        start += prefix.length();
        int end = value.indexOf(suffix, start);
        return end < 0 ? "" : value.substring(start, end);
    }
}
