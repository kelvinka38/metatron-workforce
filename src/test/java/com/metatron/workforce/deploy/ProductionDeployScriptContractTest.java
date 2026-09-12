package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects production from recreating execution substrates before fail-closed dependencies are present. */
final class ProductionDeployScriptContractTest {
    @Test
    void exactShaCompatibilityDeployIsPreflightedAndIndependentOfMutableLocalHead() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy-production-sha.sh"));

        int sandboxCheck = script.indexOf("production preflight failed: METATRON_SANDBOX_TOKEN is required");
        int repositoryCheck = script.indexOf("production preflight failed: Repository Control Plane credential is required");
        int materialize = script.indexOf("git -C \"$ROOT_DIR\" archive --format=tar \"$SHA\"");
        int build = script.indexOf("./gradlew --no-daemon clean build");
        int composeMutation = script.indexOf("up -d --no-build --force-recreate workforce-sandbox workforce");

        assertTrue(sandboxCheck >= 0, "deploy script must fail closed when sandbox token is absent");
        assertTrue(repositoryCheck >= 0, "deploy script must fail closed when repository credential is absent");
        assertTrue(materialize > repositoryCheck, "immutable deployment source must materialize only after preflight");
        assertTrue(build > materialize, "build must execute only inside materialized exact-SHA deployment workspace");
        assertTrue(composeMutation > build, "container mutation must happen only after preflight/build");
        assertTrue(script.contains("read_env_value GITHUB_TOKEN"),
                "repository credential readiness must use the canonical production env resolution path");
        assertTrue(script.contains("SANDBOX_HEALTH"), "deploy script must verify sandbox health after rollout");
        assertTrue(script.contains("LOCAL_DEPLOY_SHARED_HEAD_INDEPENDENCE=PASS"));
        assertFalse(script.contains("requested SHA $SHA is not current local HEAD"),
                "exact-SHA deployment must not depend on an unrelated mutable checkout HEAD");
        assertFalse(script.contains("git diff --quiet"),
                "exact-SHA deployment must not depend on unrelated dirty working tree state");
    }
}
