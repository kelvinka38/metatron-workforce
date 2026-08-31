package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.ExecutionAttemptStore;
import com.metatron.workforce.execution.FileExecutionAttemptStore;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.observation.FileObservationStateStore;
import com.metatron.workforce.observation.ObservationClosureService;
import com.metatron.workforce.observation.ObservationStateStore;
import com.metatron.workforce.observation.ObservationVerifier;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
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
    ManagementStateStore managementStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_MANAGEMENT_STATE_PATH",
                "/var/lib/metatron-workforce/management-state.json");
        return new FileManagementStateStore(Path.of(configured));
    }

    @Bean
    ManagementAutonomyService managementAutonomyService(ManagementStateStore store) {
        return new ManagementAutonomyService(store);
    }

    @Bean
    AutonomyCoordinationStateStore autonomyCoordinationStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_AUTONOMY_COORDINATION_STATE_PATH",
                "/var/lib/metatron-workforce/autonomy-coordination-state.json");
        return new FileAutonomyCoordinationStateStore(Path.of(configured));
    }

    @Bean
    AutonomyCoordinationService autonomyCoordinationService(AutonomyCoordinationStateStore store) {
        return new AutonomyCoordinationService(store);
    }

    @Bean
    AutonomySafetyStateStore autonomySafetyStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_AUTONOMY_SAFETY_STATE_PATH",
                "/var/lib/metatron-workforce/autonomy-safety-state.json");
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
    AutonomySchedulingStateStore autonomySchedulingStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_AUTONOMY_SCHEDULING_STATE_PATH",
                "/var/lib/metatron-workforce/autonomy-scheduling-state.json");
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
    ObservationStateStore observationStateStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_OBSERVATION_STATE_PATH",
                "/var/lib/metatron-workforce/observation-state.json");
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
    ExecutionAttemptStore executionAttemptStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_EXECUTION_ATTEMPT_STATE_PATH",
                "/var/lib/metatron-workforce/execution-attempts.json");
        return new FileExecutionAttemptStore(Path.of(configured));
    }

    @Bean
    ExecutionAttemptService executionAttemptService(ExecutionAttemptStore store) {
        return new ExecutionAttemptService(store);
    }

    @Bean
    RuntimePersistenceStore runtimePersistenceStore() {
        String configured = System.getenv().getOrDefault(
                "METATRON_RUNTIME_STATE_DIR",
                "/var/lib/metatron-workforce/runtime-state");
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
                                                         List<AutonomousStaffingPolicy> policies) {
        return new AutonomousStaffingService(core, policies);
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
