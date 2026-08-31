package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Production composition for persistent Workforce management state. */
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

    @Bean(destroyMethod = "close")
    AutonomousManagementRunner autonomousManagementRunner(
            ManagementAutonomyService management,
            ExecutionPlanProposalService planner,
            List<AutonomousExecutionCapability> capabilities) {
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, planner, capabilities, Clock.systemUTC());
        runner.start();
        return runner;
    }
}
