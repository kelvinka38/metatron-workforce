package com.metatron.workforce.phase6;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataVisibilityPolicyTest {

    private final DataVisibilityPolicy policy = new DataVisibilityPolicy();

    @Test
    void sameOrganizationCanSeeResource() {
        assertTrue(policy.evaluate("ORG-A", "ORG-A").visible());
    }

    @Test
    void differentOrganizationCannotSeeResource() {
        assertFalse(policy.evaluate("ORG-A", "ORG-B").visible());
    }
}
