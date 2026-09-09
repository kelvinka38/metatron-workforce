package com.metatron.workforce.workplace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalWorkforceGroundingPathTest {
    @Test
    void workforceDomainUsesActualCanonicalWorkforceSotFilename() {
        assertEquals("05_WORKFORCE/WORKFORCE_SOT.md",
                GitHubCanonicalInstitutionalRoleGrounding.canonicalSotPath("05_WORKFORCE"));
        assertEquals("06_GATEWAY/SOT.md",
                GitHubCanonicalInstitutionalRoleGrounding.canonicalSotPath("06_GATEWAY"));
    }
}
