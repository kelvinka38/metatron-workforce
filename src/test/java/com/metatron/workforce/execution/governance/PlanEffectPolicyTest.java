package com.metatron.workforce.execution.governance;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanEffectPolicyTest {

    @Test
    void mutatingGeneralWorkspacePlanAllowsDeterministicProjectPreparation() {
        ExecutionWorkSpec prepare = new ExecutionWorkSpec(
                "general-engineering-workspace-execution-prepare",
                "PREPARE PHASE. Create the minimal project scaffold for the carried work product.",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of("general-engineering-workspace-execution-produce"),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("project manifest exists"),
                List.of("workspace-phase:prepare", "workspace-requirement:project-manifest"));

        assertTrue(PlanEffectPolicy.allowedActions(prepare).contains("workspace.project.prepare"));
        assertTrue(PlanEffectPolicy.allowedActions(prepare).contains("workspace.file.write"));
        assertTrue(PlanEffectPolicy.allowedActions(prepare).contains("workspace.git.run"));
    }

    @Test
    void readOnlyGeneralWorkspacePlanDoesNotPermitMutatingProjectPreparation() {
        ExecutionWorkSpec readOnly = new ExecutionWorkSpec(
                "read",
                "Inspect carried workspace",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("inspection complete"),
                List.of("workspace evidence"));

        assertFalse(PlanEffectPolicy.allowedActions(readOnly).contains("workspace.project.prepare"));
    }
}
