package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.AuthorityManifestCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectCodingIngressValidationTest {
    @Test
    void directCodingAcceptsOnlyVerifiedClientsAndFounderRepositoryNamespace() {
        assertEquals("chatgpt", DirectCodingIngressService.validateClient("chatgpt"));
        assertEquals("claude", DirectCodingIngressService.validateClient("CLAUDE"));
        assertEquals("kelvinka38/bios", DirectCodingIngressService.validateRepository("kelvinka38/bios"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateRepository("other-owner/bios"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateClient("anonymous"));
    }

    @Test
    void objectiveIdentityIsBoundToVerifiedClient() {
        String id = "direct-mcp:gemini:123e4567-e89b-12d3-a456-426614174000";
        assertEquals(id, DirectCodingIngressService.validateObjective("gemini", id));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateObjective("claude", id));
    }

    @Test
    void directSurfaceExcludesReleaseAndHostOperations() {
        assertEquals("workspace.file.patch", DirectCodingIngressService.validateAction("workspace.file.patch"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateAction("workforce_deploy_local_sha"));
        assertThrows(SecurityException.class, () -> DirectCodingIngressService.validateAction("workspace_write_file"));
    }

    @Test
    void privateNetworkGuardAndFounderAuthorityCoverBios() {
        assertTrue(DirectCodingIngressController.privateOrLoopback("127.0.0.1"));
        assertTrue(DirectCodingIngressController.privateOrLoopback("172.20.0.8"));
        assertFalse(DirectCodingIngressController.privateOrLoopback("8.8.8.8"));
        AuthorityManifestCatalog catalog = AuthorityManifestCatalog.classpath();
        assertEquals("repository:kelvinka38/*", catalog.resolve("kelvinka38/bios").targetScope());
        assertEquals("repository:kelvinka38/*", catalog.resolve("kelvinka38/universal").targetScope());
        assertThrows(RuntimeException.class, () -> catalog.resolve("someone-else/bios"));
    }
}
