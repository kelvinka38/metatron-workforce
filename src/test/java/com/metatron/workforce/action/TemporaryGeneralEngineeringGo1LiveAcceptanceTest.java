package com.metatron.workforce.action;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guard for the live GO1 completion bug discovered during Coding Capability closure. */
class TemporaryGeneralEngineeringGo1LiveAcceptanceTest {
    @Test
    void githubSourceProvenanceIsNotPromotedIntoReleaseArtifactIdentity() throws Exception {
        Path source = Path.of(System.getProperty("user.dir"),
                "src/main/java/com/metatron/workforce/management/AutonomyCoordinationService.java");
        String body = Files.readString(source);
        assertTrue(body.contains("artifact(verified, \"source-sha:\", \"HIGHWAY_SOURCE_SHA=\")"),
                "release source identity must come only from release-plane evidence");
        assertFalse(body.contains("artifact(verified, \"source-sha:\", \"github-source-sha:\", \"HIGHWAY_SOURCE_SHA=\")"),
                "GitHub base-source provenance must not activate exact release-artifact identity enforcement");
    }
}
