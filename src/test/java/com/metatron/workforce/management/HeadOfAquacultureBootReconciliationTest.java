package com.metatron.workforce.management;

import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.operating.WorkerConstitutionRuntimeMaterializer;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P2: with no Objective at all, application start leaves the Head of Aquaculture ACTIVE with the full approved
 * capability bundle (including aquaculture.domain.planning), the domain-head profile and its resource scope;
 * repeated starts over the same durable state converge on the same single identity (CanonicalGatewayHeadBootstrap pattern).
 */
class HeadOfAquacultureBootReconciliationTest {
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;

    @TempDir Path temp;

    private record Boot(WorkforceCoreService core, WorkerRuntimeProfileBindingService profiles,
                        WorkerResourceScopeService scopes, RuntimeRegistry registry,
                        WorkerConstitutionRuntimeMaterializer constitution, HeadOfAquacultureBootstrapStatus status,
                        ApplicationRunner runner) {}

    private Boot boot(boolean enabled) {
        WorkforceCoreService core = new WorkforceCoreService(new FileWorkforceCoreStateStore(temp.resolve("core.json")));
        WorkerRuntimeProfileBindingService profiles = new WorkerRuntimeProfileBindingService(temp.resolve("bindings.tsv"));
        WorkerResourceScopeService scopes = new WorkerResourceScopeService(temp.resolve("scopes.tsv"));
        AutonomousStaffingService staffing = new AutonomousStaffingService(core,
                List.of(new AquacultureHeadStaffingPolicy()), profiles, WorkerConstitutionService.inMemory(), scopes);
        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeCapacityCoordinator runtime = new RuntimeCapacityCoordinator(registry);
        WorkerConstitutionRuntimeMaterializer constitution = mock(WorkerConstitutionRuntimeMaterializer.class);
        HeadOfAquacultureBootstrapStatus status = new HeadOfAquacultureBootstrapStatus();
        ApplicationRunner runner = new LiveManagementConfiguration().headOfAquacultureReconciliation(
                staffing, new AquacultureHeadAppointmentCapability(core, profiles, scopes), core, runtime,
                constitution, status, enabled);
        return new Boot(core, profiles, scopes, registry, constitution, status, runner);
    }

    @Test
    void startupWithoutObjectiveStaffsHoaIdempotentlyAcrossRestarts() throws Exception {
        Boot first = boot(true);
        first.runner().run(null);
        first.runner().run(null);
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, first.status().snapshot().state(),
                first.status().snapshot().detail());
        assertStaffed(first);
        verify(first.constitution(), atLeastOnce()).materialize(eq(HOA), any(), any());

        Boot restarted = boot(true);
        restarted.runner().run(null);
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, restarted.status().snapshot().state());
        assertStaffed(restarted);
        assertEquals(1, restarted.core().allWorkers().stream().filter(w -> HOA.equals(w.workerId())).count());
        assertEquals(1, restarted.core().participations(HOA).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE).count());
    }

    @Test
    void disabledBootstrapDoesNotAppointAndIsRecorded() throws Exception {
        Boot disabled = boot(false);
        disabled.runner().run(null);
        assertEquals(HeadOfAquacultureBootstrapStatus.State.DISABLED, disabled.status().snapshot().state());
        assertTrue(disabled.core().allWorkers().stream().noneMatch(w -> HOA.equals(w.workerId())));
        verify(disabled.constitution(), never()).materialize(any(), any(), any());
    }

    @Test
    void staffingFailureIsDegradedAndDoesNotEscapeTheRunner() {
        AutonomousStaffingService staffing = mock(AutonomousStaffingService.class);
        doThrow(new IllegalStateException("POLICY_IDENTITY_CONFLICT")).when(staffing).ensureStaffed(any(), any());
        HeadOfAquacultureBootstrapStatus status = new HeadOfAquacultureBootstrapStatus();
        ApplicationRunner runner = new LiveManagementConfiguration().headOfAquacultureReconciliation(
                staffing, mock(AquacultureHeadAppointmentCapability.class), mock(WorkforceCoreService.class),
                mock(RuntimeCapacityCoordinator.class), mock(WorkerConstitutionRuntimeMaterializer.class), status, true);
        assertDoesNotThrow(() -> runner.run(null));
        assertEquals(HeadOfAquacultureBootstrapStatus.State.DEGRADED, status.snapshot().state());
        assertTrue(status.snapshot().detail().contains("POLICY_IDENTITY_CONFLICT"));
    }

    private static void assertStaffed(Boot boot) {
        WorkforceCoreService.Worker worker = boot.core().worker(HOA);
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, worker.status());
        Set<String> capabilities = boot.core().capabilities(HOA).stream()
                .filter(c -> c.level() >= 1.0).map(WorkforceCoreService.Capability::capabilityRef)
                .collect(Collectors.toSet());
        assertTrue(capabilities.containsAll(Set.of(
                AquacultureHeadAppointmentCapability.CAPABILITY,
                AquacultureDomainPlanningCapability.CAPABILITY,
                "aquaculture.reporting", "aquaculture.delegation", "research.web.read", "repository.bios.proposal")),
                capabilities.toString());
        assertEquals(WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE,
                boot.profiles().requireBinding(HOA).profile().profileRef());
        WorkerResourceScopeService.Scope scope = boot.scopes().find(HOA).orElseThrow();
        assertEquals(AquacultureHeadStaffingPolicy.REPOSITORIES, scope.repositories());
        assertEquals(AquacultureHeadStaffingPolicy.WRITE_PATH_PREFIXES, scope.writePathPrefixes());
        assertTrue(boot.core().availability(HOA).orElseThrow().available());
        assertTrue(boot.registry().runningForWorker(HOA).isPresent(), "HOA runtime capacity must be running for dispatch");
    }
}
