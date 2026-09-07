package com.metatron.workforce.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionJournalEvidenceContinuityTest {
    @TempDir Path temp;

    @Test
    void rehydratesEvidenceAcrossDifferentWorkStepsAndAttemptsForSameObjective() {
        ActionJournal.FileJournal journal = new ActionJournal.FileJournal(temp);
        String objective = "objective-1";

        journal.append(objective, "step-search", "worker", "assignment-1", "auth", "idem-1",
                cycle(1, "workspace.file.search",
                        List.of("action-fabric:action=workspace.file.search:success=true",
                                "search-evidence:first-attempt")));
        journal.append(objective, "step-test", "worker", "assignment-2", "auth", "idem-2",
                cycle(1, "workspace.test.run",
                        List.of("action-fabric:action=workspace.test.run:success=true",
                                "test-evidence:second-attempt")));

        List<String> evidence = journal.objectiveEvidenceReferences(objective);

        assertTrue(evidence.contains("action-fabric:action=workspace.file.search:success=true"));
        assertTrue(evidence.contains("search-evidence:first-attempt"));
        assertTrue(evidence.contains("action-fabric:action=workspace.test.run:success=true"));
        assertTrue(evidence.contains("test-evidence:second-attempt"));
        assertTrue(evidence.stream().anyMatch(v -> v.contains("action-journal:action=workspace.file.search")));
        assertTrue(evidence.stream().anyMatch(v -> v.contains("action-journal:action=workspace.test.run")));
    }

    private static CognitiveWorkerRuntime.Cycle cycle(int number, String actionRef, List<String> evidence) {
        return new CognitiveWorkerRuntime.Cycle(
                number,
                new CognitiveWorkerRuntime.Thought(actionRef, Map.of(), "test"),
                ActionFabric.ActionObservation.success(actionRef, "ok", Map.of(), evidence),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
    }
}
