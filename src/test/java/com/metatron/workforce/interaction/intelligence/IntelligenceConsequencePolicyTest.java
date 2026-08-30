package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceConsequencePolicyTest {
    @Test
    void resourceDepthNeverDeterminesInstitutionalConsequence() {
        assertEquals("LOW", IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.DISCUSSION));
        assertEquals("MEDIUM", IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING));
        assertThrows(IllegalArgumentException.class,
                () -> IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.DECISION));
        assertThrows(IllegalArgumentException.class,
                () -> IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.EXECUTION));
    }
}
