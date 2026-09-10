package com.metatron.workforce.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static regression guard for the SoT execution choke points. */
class SotEnforcementArchitectureTest {
    @Test
    void noProductionCodeInvokesActionDirectlyOutsideActionFabric() throws Exception {
        List<String> violations = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (path.getFileName().toString().equals("ActionFabric.java")) continue;
                String text = Files.readString(path);
                if (text.contains(".invoke(request)") && text.contains("ActionFabric.Action")) {
                    violations.add(path.toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "direct Action invocation bypasses ActionFabric: " + violations);
    }

    @Test
    void cognitiveRuntimeContainsGovernanceDenialAndPermitBoundary() throws Exception {
        String runtime = Files.readString(Path.of(
                "src/main/java/com/metatron/workforce/action/CognitiveWorkerRuntime.java"));
        assertTrue(runtime.contains("ExecutionGate"));
        assertTrue(runtime.contains("GovernanceDeniedException"));
        assertTrue(runtime.contains("executionGate.authorize(intent)"));

        String fabric = Files.readString(Path.of(
                "src/main/java/com/metatron/workforce/action/ActionFabric.java"));
        assertTrue(fabric.contains("EXECUTION_PERMIT_REQUIRED"));
        assertTrue(fabric.contains("executionGate.requirePermitMatches"));
    }

    @Test
    void productionCompositionUsesGovernedAdmissionAndAttemptBinding() throws Exception {
        String config = Files.readString(Path.of(
                "src/main/java/com/metatron/workforce/management/LiveManagementConfiguration.java"));
        assertTrue(config.contains("new ExecutionAdmissionService(governance)"));
        assertTrue(config.contains("governancePlans, governanceAttempts"));
    }
}
