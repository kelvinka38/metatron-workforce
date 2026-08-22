package com.metatron.workforce.phase9;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Phase9D3RegressionGateMarkerTest {

    @Test
    void d3RegistryIsPresentOnTheRegressionPath() {
        assertEquals(8, Phase9DependencyRegistry.canonicalDependencies().size());
    }
}
