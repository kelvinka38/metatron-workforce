package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntelligenceRuntimeConfigurationTest {

    @Test
    void cognitionKillSwitchOverridesStaleConfiguredUrl() {
        assertFalse(IntelligenceRuntimeConfiguration.cognitionConfigured(false, "http://5.223.43.92:8091"));
    }

    @Test
    void cognitionRequiresExplicitEnableAndNonBlankUrl() {
        assertFalse(IntelligenceRuntimeConfiguration.cognitionConfigured(true, ""));
        assertFalse(IntelligenceRuntimeConfiguration.cognitionConfigured(true, "   "));
        assertTrue(IntelligenceRuntimeConfiguration.cognitionConfigured(true, "http://replacement-cognition-node:8091"));
    }
}
