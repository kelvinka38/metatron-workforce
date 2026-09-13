package com.metatron.workforce.execution.governance;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayDirectorAuthorityManifestTest {

    @Test
    void gatewayDirectorRoleAndPositionResolveToCanonicalAppointmentAuthority() {
        AuthorityManifestCatalog catalog = AuthorityManifestCatalog.classpath();

        AuthorityManifestCatalog.Manifest byRole = catalog.resolve("ROLE-HEAD-OF-GATEWAY");
        AuthorityManifestCatalog.Manifest byPosition = catalog.resolve("position:gateway-director");

        assertEquals("metatron-founder-gateway-director-appointment", byRole.manifestId());
        assertEquals(byRole.manifestId(), byPosition.manifestId());
        assertEquals("workforce:gateway-director-appointment", byRole.targetScope());

        Set<String> authorityPaths = byRole.sources().stream()
                .map(AuthorityManifestCatalog.Source::artifactPath)
                .collect(Collectors.toSet());
        assertTrue(authorityPaths.contains("06_GATEWAY/SOT.md"));
        assertTrue(authorityPaths.contains("06_GATEWAY/CONTRACTS.md"));
        assertTrue(authorityPaths.contains("05_WORKFORCE/WORKFORCE_SOT.md"));
        assertTrue(authorityPaths.contains("05_WORKFORCE/WORKFORCE_AUTHORIZATION_EXECUTION_ATTRIBUTION_ARCHITECTURE.md"));
        assertTrue(authorityPaths.contains("14_EXECUTION/SOT.md"));
    }
}
