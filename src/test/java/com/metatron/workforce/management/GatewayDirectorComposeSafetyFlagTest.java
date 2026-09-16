package com.metatron.workforce.management;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayDirectorComposeSafetyFlagTest {
    @Test
    void composeExposesGatewayBootstrapSafetyFlagDefaultingTrue() throws Exception {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"));
        assertTrue(compose.contains(
                "METATRON_BOOTSTRAP_GATEWAY_HEAD: \"${METATRON_BOOTSTRAP_GATEWAY_HEAD:-true}\""));
    }
}
