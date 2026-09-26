package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.operating.PositionAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Intelligence-owned proposal service consumed asynchronously by Workforce management. */
@Configuration
public class ExecutionPlanningConfiguration {
    @Bean
    ExecutionPlanProposalService executionPlanProposalService(
            InstitutionalIntelligenceRuntime intelligenceRuntime,
            ObjectMapper objectMapper,
            PositionAddressResolver positionAddresses,
            List<PositionWorkRoute> positionWorkRoutes) {
        ExecutionPlanProposalService frontierPlanner = new ExecutionWorkPlanner(
                intelligenceRuntime.fabric(),
                intelligenceRuntime.configuredProviders().size(),
                objectMapper);
        ExecutionPlanProposalService founderWorkerPlanner =
                new FounderWorkerExecutionPlanProposalService(frontierPlanner, positionAddresses, positionWorkRoutes);
        return new GeneralActionComposingExecutionPlanProposalService(founderWorkerPlanner);
    }
}
