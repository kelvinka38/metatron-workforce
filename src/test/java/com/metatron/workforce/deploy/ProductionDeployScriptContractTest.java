package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects production from recreating execution substrates before fail-closed dependencies are present. */
final class ProductionDeployScriptContractTest {
    @Test
    void infrastructurePreflightsRunBeforeBuildAndContainerMutation() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy-production-sha.sh"));

        int sandboxCheck = script.indexOf("production preflight failed: METATRON_SANDBOX_TOKEN is required");
        int repositoryCheck = script.indexOf("production preflight failed: Repository Control Plane credential is required");
        int build = script.indexOf("./gradlew --no-daemon clean build");
        int composeMutation = script.indexOf("up -d --build workforce");

        assertTrue(sandboxCheck >= 0, "deploy script must fail closed when sandbox token is absent");
        assertTrue(repositoryCheck >= 0, "deploy script must fail closed when repository credential is absent");
        assertTrue(build > sandboxCheck, "sandbox token preflight must happen before build");
        assertTrue(build > repositoryCheck, "repository control plane preflight must happen before build");
        assertTrue(composeMutation > build, "container mutation must happen only after preflight/build");
        assertTrue(script.contains("read_env_value GITHUB_TOKEN"),
                "repository credential readiness must use the canonical production env resolution path");
        assertTrue(script.contains("SANDBOX_HEALTH"), "deploy script must verify sandbox health after rollout");
    }
}
