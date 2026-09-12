package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class ProductionDeployScriptContractTest {
    @Test
    void compatibilityDeployDelegatesProductionMutationExclusivelyToHighway() throws Exception {
        String script = Files.readString(Path.of("deploy/deploy-production-sha.sh"));
        assertTrue(script.contains("submit --kind workforce-build"));
        assertTrue(script.contains("submit --kind workforce-deploy"));
        assertTrue(script.contains("--dependency \"$BUILD_ID\""));
        assertTrue(script.contains("LOCAL_DEPLOY_DELEGATED_TO_HIGHWAY=PASS"));
        assertTrue(script.contains("PROD_WORKFORCE_SINGLE_MUTATION_AUTHORITY=PASS"));
        assertFalse(script.contains("docker compose"));
        assertFalse(script.contains("--force-recreate workforce-sandbox workforce"));
        assertFalse(script.contains("./gradlew --no-daemon clean build"));
    }

    @Test
    void immutableHighwayReleasePublicationDoesNotDependOnMutableCheckoutHead() throws Exception {
        String script = Files.readString(Path.of("highway/publish-release.sh"));
        assertTrue(script.contains("rev-parse \"${SHA}^{commit}\""));
        assertTrue(script.contains("git -C \"$CHECKOUT\" archive \"$SHA\""));
        assertTrue(script.contains("HIGHWAY_RELEASE_SHARED_HEAD_INDEPENDENCE=PASS"));
        assertFalse(script.contains("rev-parse HEAD"));
    }
}
