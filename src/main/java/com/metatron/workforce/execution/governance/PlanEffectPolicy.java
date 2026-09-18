package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.util.Set;

/** Small deterministic mapping from approved Work capability to concrete governed effect classes. */
public final class PlanEffectPolicy {
    public static final Set<String> GENERAL_WORKSPACE_MUTATIONS = Set.of(
            "workspace.file.patch",
            "workspace.file.write",
            "workspace.project.prepare",
            "workspace.process.run",
            "workspace.shell.run",
            "workspace.git.run",
            "workspace.github.pr.publish"
    );

    private PlanEffectPolicy() {}

    public static Set<String> allowedActions(ExecutionWorkSpec work) {
        if (work.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY) return Set.of();
        if ("execution.general.workspace".equals(work.requiredCapability())) return GENERAL_WORKSPACE_MUTATIONS;
        return Set.of("capability:" + work.requiredCapability());
    }

    public static Set<String> allowedScopes(ExecutionWorkSpec work, AuthoritySnapshot snapshot) {
        if (work.target() == null || work.target().isBlank()) return Set.of(snapshot.targetScope());
        return Set.of(work.target().trim(), snapshot.targetScope());
    }
}
