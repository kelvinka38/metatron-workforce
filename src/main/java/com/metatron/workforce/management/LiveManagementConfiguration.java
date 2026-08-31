package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAdmissionService;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Production composition for persistent Workforce management and scheduler/transport state. */
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

    @Bean(destroyMethod = "close")
    AutonomousManagementRunner autonomousManagementRunner(
            ManagementAutonomyService management,
            ExecutionPlanProposalService planner,
            List<AutonomousExecutionCapability> capabilities,
            AutonomyCoordinationService coordination,
            WorkforceCoreService core) {
        Clock clock = Clock.systemUTC();
        ExecutionAdmissionService admission = new ExecutionAdmissionService();
        List<AutonomousExecutionCapability> governedCapabilities = capabilities.stream()
                .map(capability -> (AutonomousExecutionCapability) new GovernedAutonomousExecutionCapability(
                        capability, core, admission, clock))
                .toList();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, planner, governedCapabilities, coordination, clock);
        runner.start();
        return runner;
    }
}
