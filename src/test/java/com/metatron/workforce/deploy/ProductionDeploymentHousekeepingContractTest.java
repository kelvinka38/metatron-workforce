package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class ProductionDeploymentHousekeepingContractTest {
    @Test
    void mutationTaskUsesBoundedImageGcAndDefersExternalAcceptance() throws Exception {
        String deploy = Files.readString(Path.of("scripts/highway-tasks/workforce-deploy.sh"));
        assertTrue(deploy.contains("HOST_STORAGE_GC=PASS"));
        assertTrue(deploy.contains("docker image rm"));
        assertTrue(deploy.contains("removed_tags=$removed"));
        assertTrue(deploy.contains("docker ps -a --format '{{.Image}}'"));
        assertTrue(deploy.contains("EXTERNAL ACCEPTANCE DEFERRED TO HIGHWAY FANOUT"));
        assertFalse(deploy.contains("https://gate.metatron.vn"));
        assertFalse(deploy.contains("api.github.com/zen"));
        assertFalse(deploy.contains("telegram/webhook"));
    }

    @Test
    void deployFanoutRetainsExternalAcceptanceJobs() throws Exception {
        String registry = Files.readString(Path.of("highway/task-registry.json"));
        assertTrue(registry.contains("production-highway-fast"));
        assertTrue(registry.contains("work-observability"));
        assertTrue(registry.contains("control-room-fast"));
        assertTrue(registry.contains("runtime-closure"));
    }
}
