package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects production from recreating the sandbox before its fail-closed token is present. */
final class ProductionDeployScriptContractTest {
    @Test
    void sandboxTokenPreflightRunsBeforeBuildAndContainerMutation() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy-production-sha.sh"));

        int tokenCheck = script.indexOf("production preflight failed: METATRON_SANDBOX_TOKEN is required");
        int build = script.indexOf("./gradlew --no-daemon clean build");
        int composeMutation = script.indexOf("up -d --build workforce");

        assertTrue(tokenCheck >= 0, "deploy script must fail closed when sandbox token is absent");
        assertTrue(build > tokenCheck, "sandbox token preflight must happen before build");
        assertTrue(composeMutation > build, "container mutation must happen only after preflight/build");
        assertTrue(script.contains("SANDBOX_HEALTH"), "deploy script must verify sandbox health after rollout");
    }
}
