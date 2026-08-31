package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.ExecutionAttemptStore;
import com.metatron.workforce.execution.FileExecutionAttemptStore;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.runtime.FileRuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimePersistenceStore;
import com.metatron.workforce.runtime.RuntimeRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Production composition for persistent Workforce management, Execution attempts and runtime capacity. */
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

    @Bean(destroyMethod = "close")
    AutonomousManagementRunner autonomousManagementRunner(
            ManagementAutonomyService management,
            ExecutionPlanProposalService planner,
            List<AutonomousExecutionCapability> capabilities,
            AutonomyCoordinationService coordination,
            WorkforceCoreService core,
            AutonomousStaffingService staffing,
            ExecutionAdmissionService admission,
            ExecutionAttemptService attempts,
            RuntimeCapacityCoordinator runtimeCapacity) {
        Clock clock = Clock.systemUTC();
        List<AutonomousExecutionCapability> governedCapabilities = capabilities.stream()
                .map(capability -> (AutonomousExecutionCapability) new GovernedAutonomousExecutionCapability(
                        capability, core, admission, clock, staffing, attempts, runtimeCapacity))
                .toList();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, planner, governedCapabilities, coordination, clock);
        runner.start();
        return runner;
    }
}
