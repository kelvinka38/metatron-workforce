package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RC-05/RC-06: repository execution boundary is exactly the approved four-repository scope. */
final class CanonicalRepositoryScopeTest {
    @Test
    void acceptsExactlyTheFourCanonicalRepositories() {
        List<String> expected = List.of(
                "kelvinka38/universal",
                "kelvinka38/metatron-institution",
                "kelvinka38/metatron-workforce",
                "kelvinka38/bios");
        assertEquals(4, CanonicalRepositoryScope.REPOSITORIES.size());
        expected.forEach(repository -> {
            assertTrue(CanonicalRepositoryScope.allowed(repository));
            assertEquals(repository, CanonicalRepositoryScope.requireAllowed(repository));
        });
    }

    @Test
    void rejectsSameOwnerUnknownRepositoryAndOtherOwners() {
        assertThrows(SecurityException.class,
                () -> CanonicalRepositoryScope.requireAllowed("kelvinka38/random-other-repo"));
        assertThrows(SecurityException.class,
                () -> CanonicalRepositoryScope.requireAllowed("someone/metatron-workforce"));
    }

    @Test
    void normalizesGithubUrlWithoutWideningScope() {
        assertEquals("kelvinka38/bios",
                CanonicalRepositoryScope.requireAllowed("https://github.com/kelvinka38/bios.git"));
    }
}
