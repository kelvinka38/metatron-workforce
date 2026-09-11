package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.ChannelInteractionIngressService;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Machine-verifiable retirement checks for production-owned duplicate provider control. */
final class CognitiveRuntimeP14RetirementTest {

    @Test
    void providerRouterOwnershipRemainsInsideInstitutionalIntelligenceRuntime() {
        assertTrue(hasFieldAssignableTo(InstitutionalIntelligenceRuntime.class, LlmProviderRouter.class),
                "the shared institutional runtime must own the provider router");
        assertFalse(hasFieldAssignableTo(ChannelInteractionIngressService.class, LlmProviderRouter.class),
                "channel ingress must not own a provider router");
        assertFalse(hasFieldAssignableTo(MetatronIntelligenceResponder.class, LlmProviderRouter.class),
                "the production responder must consume shared Intelligence rather than own a router field");
    }

    @Test
    void executionPlanningConsumesTheSharedInstitutionalRuntime() {
        Method bean = Arrays.stream(ExecutionPlanningConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("executionPlanProposalService"))
                .findFirst()
                .orElseThrow();

        assertTrue(Arrays.asList(bean.getParameterTypes()).contains(InstitutionalIntelligenceRuntime.class),
                "execution planning must consume the shared institutional Intelligence runtime");
        assertFalse(Arrays.asList(bean.getParameterTypes()).contains(LlmProviderRouter.class),
                "execution planning must not accept a private provider router");
    }

    private static boolean hasFieldAssignableTo(Class<?> owner, Class<?> fieldType) {
        for (Field field : owner.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())) return true;
        }
        return false;
    }
}
