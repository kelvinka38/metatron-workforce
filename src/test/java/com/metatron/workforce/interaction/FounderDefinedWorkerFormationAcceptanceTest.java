package com.metatron.workforce.interaction;

import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AutonomousStaffingService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import com.metatron.workforce.operating.FileWorkerConstitutionStateStore;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.workplace.MeetingWorkerDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** W1-W5 acceptance: arbitrary Founder-defined Worker is real, usable, and survives process replacement. */
class FounderDefinedWorkerFormationAcceptanceTest {
    @TempDir Path temp;

    @Test
    void composerArtistIsCreatedActivatedDiscoverableAndDurableAcrossRestart() {
        Harness first = harness();
        String create = first.lifecycle.handle(
                "human-primary",
                "Create for me a worker, role composer/artist. Người có tư duy âm nhạc của Michael Jackson.")
                .orElseThrow();

        assertTrue(create.startsWith("🧰 **METATRON · WORKER READY**"));
        assertTrue(create.contains("worker_id=`WORKER-COMPOSER-ARTIST`"));
        assertTrue(create.contains("role_ref=role:founder-defined:composer-artist"));
        assertTrue(create.contains("position_ref=position:founder-defined:composer-artist"));
        assertTrue(create.contains("runtime_profile=runtime-profile:founder-cognitive-worker:v1"));
        assertTrue(create.contains("runtime_state=RUNNING"));
        assertTrue(create.contains("worker_status=ACTIVE"));
        assertTrue(create.contains("formation=CREATED"));

        WorkforceCoreService.Worker worker = first.core.worker("WORKER-COMPOSER-ARTIST");
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, worker.status());
        assertTrue(first.core.capabilities(worker.workerId()).stream()
                .anyMatch(c -> FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY.equals(c.capabilityRef())));
        assertTrue(first.core.capabilities(worker.workerId()).stream()
                .anyMatch(c -> FounderDefinedWorkerFormationService.CONVERSATION_CAPABILITY.equals(c.capabilityRef())));
        assertEquals("WORKER-COMPOSER-ARTIST",
                first.directory.resolveActive("composer artist").workerId());

        String activatePronoun = first.lifecycle.handle(
                "human-primary", "Tao muốn nó đi vào hoạt động.", create).orElseThrow();
        assertTrue(activatePronoun.contains("worker_id=`WORKER-COMPOSER-ARTIST`"));
        assertTrue(activatePronoun.contains("runtime_state=RUNNING"));

        // Simulate JVM/container replacement by rebuilding every durable service from the same stores.
        Harness restarted = harness();
        String inspected = restarted.lifecycle.handle(
                "human-primary", "Show worker WORKER-COMPOSER-ARTIST canonical state").orElseThrow();
        assertTrue(inspected.contains("worker_id=`WORKER-COMPOSER-ARTIST`"));
        assertTrue(inspected.contains("worker_status=ACTIVE"));
        assertTrue(inspected.contains("runtime_state=RUNNING"));
        assertTrue(inspected.contains("constitution=position-contract:position:founder-defined:composer-artist:v1"));
        assertEquals("WORKER-COMPOSER-ARTIST",
                restarted.directory.resolveActive("composer artist").workerId());
    }

    @Test
    void malformedCreationFailsClosedInsteadOfFallingThroughToNarrativeSuccess() {
        Harness h = harness();
        assertTrue(h.lifecycle.supports("Create for me a worker"));
        String response = h.lifecycle.handle("human-primary", "Create for me a worker").orElseThrow();
        assertTrue(response.contains("WORKER NOT CREATED"));
        assertTrue(response.contains("No Worker, Participation, runtime or institutional state was created"));
        assertTrue(h.core.allWorkers().isEmpty());
    }

    private Harness harness() {
        WorkforceCoreService core = new WorkforceCoreService(new FileWorkforceCoreStateStore(temp.resolve("core.json")));
        WorkerRuntimeProfileBindingService profiles = new WorkerRuntimeProfileBindingService(temp.resolve("profiles.tsv"));
        WorkerConstitutionService constitution = new WorkerConstitutionService(
                new FileWorkerConstitutionStateStore(temp.resolve("constitution.json")));
        RuntimeRegistry registry = new RuntimeRegistry(new FileRuntimePersistenceStore(temp.resolve("runtimes")));
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(registry);
        GatewayDirectorAppointmentCapability gateway = new GatewayDirectorAppointmentCapability(core, profiles);
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new GatewayDirectorStaffingPolicy()), profiles, constitution);
        FounderDefinedWorkerFormationService founder = new FounderDefinedWorkerFormationService(
                core, profiles, constitution, runtimes);
        WorkerLifecycleControlService lifecycle = new WorkerLifecycleControlService(
                staffing, gateway, core, profiles, runtimes, founder,
                Clock.fixed(Instant.parse("2026-09-11T06:30:00Z"), ZoneOffset.UTC));
        return new Harness(core, registry, lifecycle, new MeetingWorkerDirectory(core, registry));
    }

    private record Harness(
            WorkforceCoreService core,
            RuntimeRegistry registry,
            WorkerLifecycleControlService lifecycle,
            MeetingWorkerDirectory directory) {}
}
