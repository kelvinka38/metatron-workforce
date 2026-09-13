package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.AuthorityManifestCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectCodingIngressValidationTest {
    @Test
    void directCodingAcceptsOpaqueVerifiedClientsAndDynamicRepositoryIdentifiers() {
        assertEquals("oauth-client-123", DirectCodingIngressService.validateClient("oauth-client-123"));
        assertEquals("compat:0123456789abcdef", DirectCodingIngressService.validateClient("compat:0123456789abcdef"));
        assertEquals("kelvinka38/bios", DirectCodingIngressService.validateRepository("kelvinka38/bios"));
        assertEquals("kelvinka38/random-other-repo", DirectCodingIngressService.validateRepository("kelvinka38/random-other-repo"));
        assertEquals("other-owner/private_repo", DirectCodingIngressService.validateRepository("other-owner/private_repo"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateRepository("owner/../repo"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateClient("contains space"));
    }

    @Test
    void objectiveIdentityIsBoundToVerifiedClient() {
        String client = "oauth-client-123";
        String id = "direct-mcp:" + DirectCodingIngressService.clientBinding(client)
                + ":123e4567-e89b-12d3-a456-426614174000";
        assertEquals(id, DirectCodingIngressService.validateObjective(client, id));
        assertThrows(SecurityException.class,
                () -> DirectCodingIngressService.validateObjective("different-client", id));

        String legacy = "direct-mcp:gemini:123e4567-e89b-12d3-a456-426614174000";
        assertEquals(legacy, DirectCodingIngressService.validateObjective("gemini", legacy));
    }

    @Test
    void directSurfaceExcludesReleaseAndHostOperations() {
        assertEquals("workspace.file.patch", DirectCodingIngressService.validateAction("workspace.file.patch"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateAction("workforce_deploy_local_sha"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateAction("workspace_write_file"));
    }

    @Test
    void privateNetworkGuardAndAuthorityManifestCoverRepositoryNamespaceOnly() {
        assertTrue(DirectCodingIngressController.privateOrLoopback("127.0.0.1"));
        assertTrue(DirectCodingIngressController.privateOrLoopback("172.20.0.8"));
        assertFalse(DirectCodingIngressController.privateOrLoopback("8.8.8.8"));

        AuthorityManifestCatalog catalog = AuthorityManifestCatalog.classpath();
        assertEquals("repository:metatron-canonical-four",
                catalog.resolve("repository:kelvinka38/bios").targetScope());
        assertEquals("repository:metatron-canonical-four",
                catalog.resolve("repository:someone-else/private-repo").targetScope());
        assertEquals("repository:metatron-canonical-four",
                catalog.resolve("kelvinka38/metatron-workforce").targetScope());
        assertThrows(RuntimeException.class, () -> catalog.resolve("someone-else/private-repo"));
        assertThrows(RuntimeException.class, () -> catalog.resolve("not-a-repository-target"));
    }
}
