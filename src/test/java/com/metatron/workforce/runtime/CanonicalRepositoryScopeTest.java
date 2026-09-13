package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repository identifiers are syntax-bounded locally; server credential decides actual access. */
final class CanonicalRepositoryScopeTest {
    @Test
    void acceptsAnySafeGithubRepositoryIdentifierWithoutStaticAllowlist() {
        assertTrue(CanonicalRepositoryScope.allowed("kelvinka38/metatron-workforce"));
        assertTrue(CanonicalRepositoryScope.allowed("kelvinka38/random-other-repo"));
        assertTrue(CanonicalRepositoryScope.allowed("another-owner/private_repo.v2"));
        assertEquals("another-owner/private_repo.v2",
                CanonicalRepositoryScope.requireAllowed("Another-Owner/Private_Repo.V2"));
    }

    @Test
    void rejectsMalformedOrTraversalLikeRepositoryIdentifiers() {
        assertFalse(CanonicalRepositoryScope.allowed("owner-only"));
        assertFalse(CanonicalRepositoryScope.allowed("owner/repo/extra"));
        assertFalse(CanonicalRepositoryScope.allowed("owner/../repo"));
        assertFalse(CanonicalRepositoryScope.allowed("https://example.com/owner/repo"));
        assertThrows(SecurityException.class,
                () -> CanonicalRepositoryScope.requireAllowed("owner/../repo"));
    }

    @Test
    void normalizesGithubUrlWithoutChangingAuthorizationSemantics() {
        assertEquals("kelvinka38/bios",
                CanonicalRepositoryScope.requireAllowed("https://github.com/KelvinKa38/BIOS.git"));
    }
}
