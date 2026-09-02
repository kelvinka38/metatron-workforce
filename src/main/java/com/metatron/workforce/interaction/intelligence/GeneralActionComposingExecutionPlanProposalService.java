package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Converts a planner-produced missing code/file/process/Git/build/test capability into the general
 * governed Action Fabric capability. Direct general-workspace plans are also marked so Observation
 * can verify them independently. This is capability composition, not fake staffing for a tool name.
 */
public final class GeneralActionComposingExecutionPlanProposalService implements ExecutionPlanProposalService {
    public static final String GENERAL_RUNTIME_MARKER = "general-action-runtime:" + GeneralWorkspaceAutonomousCapability.CAPABILITY;

    private static final Set<String> COMPOSABLE_TOKENS = Set.of(
            "repository", "repo", "file", "filesystem", "workspace", "shell", "process",
            "git", "build", "test", "code", "source", "patch", "artifact", "compile");

    private final ExecutionPlanProposalService delegate;

    public GeneralActionComposingExecutionPlanProposalService(ExecutionPlanProposalService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<ExecutionWorkSpec> propose(String caseId, NormalizedRequest request, List<String> availableCapabilities) {
        List<ExecutionWorkSpec> plan = delegate.propose(caseId, request, availableCapabilities);
        if (plan == null || plan.isEmpty()) return plan;
        Set<String> available = Set.copyOf(availableCapabilities == null ? List.of() : availableCapabilities);
        if (!available.contains(GeneralWorkspaceAutonomousCapability.CAPABILITY)) return plan;
        List<ExecutionWorkSpec> composed = new ArrayList<>(plan.size());
        for (ExecutionWorkSpec step : plan) {
            if (GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(step.requiredCapability())) {
                composed.add(markDirectGeneral(step));
                continue;
            }
            if (available.contains(step.requiredCapability()) || !composable(step)) {
                composed.add(step);
                continue;
            }
            List<String> acceptance = new ArrayList<>(step.acceptanceCriteria());
            addDistinct(acceptance, "general Action Fabric completes requested capability " + step.requiredCapability());
            List<String> evidence = new ArrayList<>(step.evidenceRequirements());
            addDistinct(evidence, "requested-capability:" + step.requiredCapability());
            addDistinct(evidence, "general-action-composition:" + step.requiredCapability());
            addDistinct(evidence, GENERAL_RUNTIME_MARKER);
            composed.add(new ExecutionWorkSpec(
                    step.stepId(), step.objective(), step.target(), GeneralWorkspaceAutonomousCapability.CAPABILITY,
                    step.dependsOn(), step.consequence(), acceptance, evidence));
        }
        return List.copyOf(composed);
    }

    private static ExecutionWorkSpec markDirectGeneral(ExecutionWorkSpec step) {
        List<String> evidence = new ArrayList<>(step.evidenceRequirements());
        addDistinct(evidence, GENERAL_RUNTIME_MARKER);
        return new ExecutionWorkSpec(
                step.stepId(), step.objective(), step.target(), step.requiredCapability(), step.dependsOn(),
                step.consequence(), step.acceptanceCriteria(), evidence);
    }

    private static void addDistinct(List<String> values, String value) {
        if (!values.contains(value)) values.add(value);
    }

    static boolean composable(ExecutionWorkSpec step) {
        String capability = step.requiredCapability().toLowerCase(Locale.ROOT);
        for (String token : COMPOSABLE_TOKENS) {
            if (capability.equals(token)
                    || capability.startsWith(token + ".")
                    || capability.endsWith("." + token)
                    || capability.contains("." + token + ".")
                    || capability.contains("_" + token)
                    || capability.contains(token + "_")) return true;
        }
        return false;
    }
}
