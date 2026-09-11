package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Intelligence-owned proposal service consumed asynchronously by Workforce management. */
@Configuration
public class ExecutionPlanningConfiguration {
    @Bean
    ExecutionPlanProposalService executionPlanProposalService(
            InstitutionalIntelligenceRuntime intelligenceRuntime,
            ObjectMapper objectMapper) {
        ExecutionPlanProposalService frontierPlanner = new ExecutionWorkPlanner(
                intelligenceRuntime.fabric(),
                intelligenceRuntime.configuredProviders().size(),
                objectMapper);
        ExecutionPlanProposalService founderWorkerPlanner =
                new FounderWorkerExecutionPlanProposalService(frontierPlanner);
        return new GeneralActionComposingExecutionPlanProposalService(founderWorkerPlanner);
    }
}
