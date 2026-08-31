package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RepositoryPullRequestAutonomousCapabilitySpringWiringTest {
    @Test
    void springContainerSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, ObjectMapper::new);
            context.register(RepositoryPullRequestAutonomousCapability.class);
            context.refresh();

            RepositoryPullRequestAutonomousCapability capability =
                    context.getBean(RepositoryPullRequestAutonomousCapability.class);
            assertNotNull(capability);
            assertEquals(RepositoryPullRequestAutonomousCapability.CAPABILITY, capability.capabilityRef());
        }
    }
}
