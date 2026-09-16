package com.metatron.workforce.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @Test
    void lifecycleInventoryIgnoresCommentsButStillFailsClosedForRealCode() throws Exception {
        String probe = """
                import importlib.util
                import sys
                from pathlib import Path

                sys.dont_write_bytecode = True
                root = Path.cwd()
                spec = importlib.util.spec_from_file_location("inventory", root / "scripts/sot_enforcement/inventory.py")
                inventory = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(inventory)

                def classify(name, text):
                    return inventory.classify(root / "src/main/java/com/example" / name, text)

                comment_only = classify("CommentOnly.java", '''
                    /** completeAutonomousObjective( and completeGraph( are documentation only. */
                    class CommentOnly {
                        // transitionAssignment( is also comment-only.
                        String url = "https://example.test/path";
                    }
                ''')
                assert not [row for row in comment_only if row["kind"].endswith("_TRANSITION")], comment_only

                actual_known = classify("ManagementAutonomyService.java",
                    "class Known { void run() { completeAutonomousObjective(); } }")
                assert len(actual_known) == 1, actual_known
                assert actual_known[0]["kind"] == "OBJECTIVE_COMPLETION_TRANSITION", actual_known
                assert actual_known[0]["known"] is True, actual_known

                actual_unknown = classify("UnownedCompletion.java",
                    "class Unknown { void run() { completeAutonomousObjective(); } }")
                assert len(actual_unknown) == 1, actual_unknown
                assert actual_unknown[0]["kind"] == "OBJECTIVE_COMPLETION_TRANSITION", actual_unknown
                assert actual_unknown[0]["known"] is False, actual_unknown
                assert actual_unknown[0]["owner"] == "UNDECLARED", actual_unknown

                assert "ObjectiveCompletionGate.java" not in inventory.COMPLETION_OWNERS
                """;

        Process process = new ProcessBuilder("python3", "-c", probe)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

}
