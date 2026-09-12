package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RepositoryPullRequestAutonomousCapabilitySpringWiringTest {
    @Test
    void springContainerSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Supplier<ObjectMapper> objectMapper = ObjectMapper::new;
            context.registerBean(ObjectMapper.class, objectMapper);
            context.registerBean(RepositoryCredentialAuthority.class, () -> new RepositoryCredentialAuthority(""));
            context.register(RepositoryPullRequestAutonomousCapability.class);
            context.refresh();

            RepositoryPullRequestAutonomousCapability capability =
                    context.getBean(RepositoryPullRequestAutonomousCapability.class);
            assertNotNull(capability);
            assertEquals(RepositoryPullRequestAutonomousCapability.CAPABILITY, capability.capabilityRef());
        }
    }
}
