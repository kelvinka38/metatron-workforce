package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class LiveManagementConfigurationTest {
    @Test
    void cognitionBoundaryIsAbsentWhenCognitionIsDisabled() {
        AutonomousExecutionCapability delegate = capability();
        WorkerIntelligenceService intelligence = request -> {
            throw new AssertionError("cognition must not be called when disabled");
        };

        assertSame(delegate, LiveManagementConfiguration.cognitionBoundary(delegate, intelligence, false));
    }

    @Test
    void cognitionBoundaryIsComposedOnlyWhenExplicitlyEnabled() {
        AutonomousExecutionCapability delegate = capability();
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "cognition:test", "bounded decision", List.of("evidence:cognition"));

        assertInstanceOf(CognitionBoundAutonomousExecutionCapability.class,
                LiveManagementConfiguration.cognitionBoundary(delegate, intelligence, true));
    }

    private static AutonomousExecutionCapability capability() {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return HostCommanderAutonomousCapability.CAPABILITY; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, "worker", "binding", "work", List.of(), "PASS");
            }
        };
    }
}
