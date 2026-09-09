package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.ExecutionAttemptStore;
import com.metatron.workforce.execution.FileExecutionAttemptStore;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.observation.FileObservationStateStore;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationStateStore;
import com.metatron.workforce.observation.ObservationVerifier;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Production composition for persistent management, governed resources, execution/runtime and Observation closure. */
@Configuration
public class LiveManagementConfiguration {
    @Bean
    ManagementStateStore managementStateStore(
            @Value("${METATRON_MANAGEMENT_STATE_PATH:/var/lib/metatron-workforce/management-state.json}") String configured) {
        return new FileManagementStateStore(Path.of(configured));
    }

    @Bean
    ManagementAutonomyService managementAutonomyService(ManagementStateStore store) {
        return new ManagementAutonomyService(store);
    }

    @Bean
    AutonomyCoordinationStateStore autonomyCoordinationStateStore(
            @Value("${METATRON_AUTONOMY_COORDINATION_STATE_PATH:/var/lib/metatron-workforce/autonomy-coordination-state.json}") String configured) {
        return new FileAutonomyCoordinationStateStore(Path.of(configured));
    }

    @Bean
    AutonomyCoordinationService autonomyCoordinationService(AutonomyCoordinationStateStore store) {
        return new AutonomyCoordinationService(store);
    }

    @Bean
    AutonomySafetyStateStore autonomySafetyStateStore(
            @Value("${METATRON_AUTONOMY_SAFETY_STATE_PATH:/var/lib/metatron-workforce/autonomy-safety-state.json}") String configured) {
        return new FileAutonomySafetyStateStore(Path.of(configured));
    }

    @Bean
    AutonomySafetyService autonomySafetyService(AutonomySafetyStateStore store) {
        Clock clock = Clock.systemUTC();
        double maxCostUnits = positiveDouble("METATRON_AUTONOMY_MAX_COST_UNITS",
                AutonomySafetyService.DEFAULT_MAX_COST_UNITS);
        int maxAttempts = positiveInt("METATRON_AUTONOMY_MAX_DISPATCH_ATTEMPTS",
                AutonomySafetyService.DEFAULT_MAX_DISPATCH_ATTEMPTS);
        long maxDurationSeconds = positiveLong("METATRON_AUTONOMY_MAX_DURATION_SECONDS",
                AutonomySafetyService.DEFAULT_MAX_DURATION.toSeconds());
        AutonomySafetyState.RiskLevel maxRisk = AutonomySafetyState.RiskLevel.valueOf(
                System.getenv().getOrDefault("METATRON_AUTONOMY_MAX_RISK",
                        AutonomySafetyService.DEFAULT_MAX_RISK.name()).trim().toUpperCase(Locale.ROOT));
        return new AutonomySafetyService(store, clock, maxCostUnits, maxAttempts,
                Duration.ofSeconds(maxDurationSeconds), maxRisk);
    }

    @Bean
    AutonomySchedulingStateStore autonomySchedulingStateStore(
            @Value("${METATRON_AUTONOMY_SCHEDULING_STATE_PATH:/var/lib/metatron-workforce/autonomy-scheduling-state.json}") String configured) {
        return new FileAutonomySchedulingStateStore(Path.of(configured));
    }

    @Bean
    AutonomySchedulingService autonomySchedulingService(WorkforceCoreService core,
                                                         AutonomySafetyService safety,
                                                         AutonomySchedulingStateStore store) {
        int parallelism = positiveInt("METATRON_AUTONOMY_MAX_PARALLELISM", 4);
        if (parallelism > 4) {
            throw new IllegalStateException("METATRON_AUTONOMY_MAX_PARALLELISM must be between 1 and 4 for this runner");
        }
        return new AutonomySchedulingService(core, safety, store, parallelism);
    }

    @Bean
    ObservationStateStore observationStateStore(
            @Value("${METATRON_OBSERVATION_STATE_PATH:/var/lib/metatron-workforce/observation-state.json}") String configured) {
        return new FileObservationStateStore(Path.of(configured));
    }

    @Bean
    ObservationClosureService observationClosureService(ObservationStateStore store,
                                                         List<ObservationVerifier> verifiers) {
        return new ObservationClosureService(store, verifiers);
    }

    @Bean
    ExecutionAdmissionService executionAdmissionService() {
        return new ExecutionAdmissionService();
    }

    @Bean
    ExecutionAttemptStore executionAttemptStore(
            @Value("${METATRON_EXECUTION_ATTEMPT_STATE_PATH:/var/lib/metatron-workforce/execution-attempts.json}") String configured) {
        return new FileExecutionAttemptStore(Path.of(configured));
    }

    @Bean
    ExecutionAttemptService executionAttemptService(ExecutionAttemptStore store) {
        return new ExecutionAttemptService(store);
    }

    @Bean
    RuntimePersistenceStore runtimePersistenceStore(
            @Value("${METATRON_RUNTIME_STATE_DIR:/var/lib/metatron-workforce/runtime-state}") String configured) {
        return new FileRuntimePersistenceStore(Path.of(configured));
    }

    @Bean
    RuntimeRegistry runtimeRegistry(RuntimePersistenceStore store) {
        return new RuntimeRegistry(store);
    }

    @Bean
    RuntimeCapacityCoordinator runtimeCapacityCoordinator(RuntimeRegistry registry) {
        return new RuntimeCapacityCoordinator(registry);
    }

    @Bean
    AutonomousStaffingService autonomousStaffingService(WorkforceCoreService core,
                                                         List<AutonomousStaffingPolicy> policies,
                                                         WorkerRuntimeProfileBindingService runtimeProfiles,
                                                         WorkerConstitutionService constitution) {
        return new AutonomousStaffingService(core, policies, runtimeProfiles, constitution);
    }

    /**
     * Reconcile the canonical Gateway Director through the governed staffing service.
     * This intentionally uses the existing formation policy instead of inventing a second Worker identity.
     */
    @Bean
    ApplicationRunner canonicalGatewayDirectorReconciliation(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability capability,
            WorkforceCoreService core,
            RuntimeCapacityCoordinator runtimeCapacity,
            @Value("${METATRON_BOOTSTRAP_GATEWAY_HEAD:true}") boolean enabled) {
        return args -> {
            if (!enabled) return;
            staffing.ensureStaffed(capability, Clock.systemUTC().instant());
            runtimeCapacity.ensureRunning(GatewayDirectorAppointmentCapability.WORKER_ID);

            // Collapse every historical/parallel Gateway Head identity onto the one governed canonical Worker.
            // Production has accumulated more than one legacy ID over earlier acceptance/bootstrap iterations,
            // so cleanup is role-based rather than hard-coded to one obsolete worker name.
            core.allWorkers().stream()
                    .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                    .filter(w -> !GatewayDirectorAppointmentCapability.WORKER_ID.equals(w.workerId()))
                    .filter(w -> core.participations(w.workerId()).stream().anyMatch(p ->
                            p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                                    && GatewayDirectorAppointmentCapability.ROLE_REF.equals(p.roleRef())))
                    .forEach(legacy -> {
                        for (WorkforceCoreService.Participation p : core.participations(legacy.workerId())) {
                            if (p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                                    && GatewayDirectorAppointmentCapability.ROLE_REF.equals(p.roleRef())) {
                                try {
                                    core.setParticipationStatus(p.participationId(),
                                            WorkforceCoreService.ParticipationStatus.ENDED);
                                } catch (RuntimeException ignored) {
                                    // Canonical Meeting resolution is deterministic even if a reservation delays cleanup.
                                }
                            }
                        }
                        try {
                            core.setWorkerStatus(legacy.workerId(), WorkforceCoreService.WorkerStatus.RETIRED);
                        } catch (RuntimeException ignored) {
                            // A live reservation may delay retirement; it must not make role resolution ambiguous.
                        }
                    });
        };
    }

    @Bean
    AutonomyEvidencePackageService autonomyEvidencePackageService(
            ManagementAutonomyService management,
            AutonomyCoordinationService coordination,
            AutonomySafetyService safety,
            ObservationClosureService observation,
            WorkforceCoreService core,
            ExecutionAttemptService attempts,
            WorkplaceContinuityService workplace) {
        return new AutonomyEvidencePackageService(management, coordination, safety, observation,
                core, attempts, workplace);
    }

    @Bean(destroyMethod = "close")
    AutonomousManagementRunner autonomousManagementRunner(
            ManagementAutonomyService management,
            ExecutionPlanProposalService planner,
            List<AutonomousExecutionCapability> capabilities,
            AutonomyCoordinationService coordination,
            ObservationClosureService observationClosure,
            AutonomySafetyService safety,
            AutonomySchedulingService scheduling,
            WorkforceCoreService core,
            AutonomousStaffingService staffing,
            ExecutionAdmissionService admission,
            ExecutionAttemptService attempts,
            RuntimeCapacityCoordinator runtimeCapacity) {
        Clock clock = Clock.systemUTC();
        List<AutonomousExecutionCapability> governedCapabilities = capabilities.stream()
                .map(capability -> (AutonomousExecutionCapability) new GovernedAutonomousExecutionCapability(
                        capability, core, admission, clock, staffing, attempts, runtimeCapacity))
                .map(capability -> (AutonomousExecutionCapability) new SafetyGovernedAutonomousExecutionCapability(
                        capability, safety, clock))
                .toList();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, planner, governedCapabilities, coordination, observationClosure, safety, clock);
        runner.configureScheduling(scheduling);
        runner.start();
        return runner;
    }

    private static double positiveDouble(String name, double fallback) {
        double value = Double.parseDouble(System.getenv().getOrDefault(name, Double.toString(fallback)));
        if (!Double.isFinite(value) || value <= 0) throw new IllegalStateException(name + " must be finite and positive");
        return value;
    }

    private static int positiveInt(String name, int fallback) {
        int value = Integer.parseInt(System.getenv().getOrDefault(name, Integer.toString(fallback)));
        if (value < 1) throw new IllegalStateException(name + " must be positive");
        return value;
    }

    private static long positiveLong(String name, long fallback) {
        long value = Long.parseLong(System.getenv().getOrDefault(name, Long.toString(fallback)));
        if (value < 1) throw new IllegalStateException(name + " must be positive");
        return value;
    }
}
