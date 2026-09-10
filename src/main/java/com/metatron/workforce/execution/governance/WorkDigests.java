package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Canonical digest for an execution work proposal; model prose outside the WorkSpec is not authority. */
public final class WorkDigests {
    private WorkDigests() {}

    public static String digest(ExecutionWorkSpec work) {
        Objects.requireNonNull(work, "work");
        return GovernanceDigests.sha256(String.join("\n",
                work.stepId(), work.objective(), work.target(), work.requiredCapability(),
                sorted(work.dependsOn()), work.consequence().name(),
                sorted(work.acceptanceCriteria()), sorted(work.evidenceRequirements())));
    }

    private static String sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values == null ? List.of() : values);
        Collections.sort(copy);
        return String.join("\u001f", copy);
    }
}
