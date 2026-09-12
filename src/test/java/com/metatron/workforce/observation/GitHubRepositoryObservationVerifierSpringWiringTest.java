package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GitHubRepositoryObservationVerifierSpringWiringTest {
    @Test
    void springContainerSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Supplier<ObjectMapper> objectMapper = ObjectMapper::new;
            context.registerBean(ObjectMapper.class, objectMapper);
            context.registerBean(RepositoryCredentialAuthority.class, () -> new RepositoryCredentialAuthority(""));
            context.register(GitHubRepositoryObservationVerifier.class);
            context.refresh();

            ObservationVerifier verifier = context.getBean(GitHubRepositoryObservationVerifier.class);
            assertNotNull(verifier);
            assertTrue(verifier instanceof GitHubRepositoryObservationVerifier);
        }
    }
}
