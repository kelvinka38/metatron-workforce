package com.metatron.workforce.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class ProductionConfigAuthorityContractTest {
    private static final String LEGACY_ENV = "/opt/metatron/metatron-workforce/.env";
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
}
