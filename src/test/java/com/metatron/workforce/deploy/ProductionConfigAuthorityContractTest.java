package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ProductionConfigAuthorityContractTest {
    private static final String LEGACY_CHECKOUT = "/opt/metatron/metatron-workforce";
    private static final String LEGACY_ENV = LEGACY_CHECKOUT + "/.env";
    private static final String CANONICAL_ENV = "$HOME/.metatron/config/workforce.env";

    @Test
    void highwayInstallMigratesLegacyConfigIntoDedicatedAuthority() throws Exception {
        String install = Files.readString(Path.of("highway/install.sh"));
        assertTrue(install.contains("PRODUCTION_CONFIG_DIR=\"${METATRON_PRODUCTION_CONFIG_DIR:-$HOME/.metatron/config}\""));
        assertTrue(install.contains("LEGACY_ENV=\"${METATRON_LEGACY_PRODUCTION_ENV_FILE:-" + LEGACY_ENV + "}\""));
        assertTrue(install.contains("PRODUCTION_ENV_MIGRATED=YES"));
        assertTrue(install.contains("chmod 600 \"$TMP_ENV\""));
    }

    @Test
    void activeHighwayAndProductionDeployNoLongerUseLegacyCheckoutEnv() throws Exception {
        String ensure = Files.readString(Path.of("highway/ensure-running.sh"));
        String deployTask = Files.readString(Path.of("scripts/highway-tasks/workforce-deploy.sh"));
        String workflow = Files.readString(Path.of(".github/workflows/production-deploy.yml"));

        assertTrue(ensure.contains(CANONICAL_ENV));
        assertTrue(deployTask.contains(CANONICAL_ENV));
        assertTrue(workflow.contains(CANONICAL_ENV));

        assertFalse(ensure.contains(LEGACY_ENV));
        assertFalse(deployTask.contains(LEGACY_ENV));
        assertFalse(workflow.contains(LEGACY_ENV));
    }

    @Test
    void activeIngressAndHighwayTasksDoNotDependOnLegacyCheckout() throws Exception {
        try (var paths = Files.walk(Path.of(".github/workflows"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(LEGACY_CHECKOUT), path.toString());
            }
        }
        try (var paths = Files.walk(Path.of("scripts/highway-tasks"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(LEGACY_CHECKOUT), path.toString());
            }
        }
        for (String path : java.util.List.of(
                "scripts/highway-intelligence-lane.sh",
                "scripts/production-acceptance-highway.sh",
                "scripts/runtime-conformance-point2.sh",
                "scripts/runtime-conformance-point5.sh",
                "scripts/highway-manual-ingress.sh")) {
            assertFalse(Files.readString(Path.of(path)).contains(LEGACY_CHECKOUT), path);
        }
        assertFalse(Files.exists(Path.of("scripts/telegram-gateway-completion.sh")));
    }
}
