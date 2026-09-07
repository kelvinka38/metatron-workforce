package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class GeneralExecutionRuntimeConfigurationTest {
    @Test
    void cognitiveWorkerFactoryConsumesInstitutionalIntelligenceInsteadOfProviderRoutes() {
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "test-intelligence", "{\"decision\":\"CONTINUE\",\"summary\":\"test\"}", List.of());
        GeneralCognitiveWorkerBrainFactory factory =
                new GeneralExecutionRuntimeConfiguration()
                        .generalCognitiveWorkerBrainFactory(intelligence, new ObjectMapper());

        assertNotNull(factory.create());
    }
}
