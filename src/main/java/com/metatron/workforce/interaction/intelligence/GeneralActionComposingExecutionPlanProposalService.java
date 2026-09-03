package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Converts a planner-produced missing code/file/process/Git/build/test capability into the general
 * governed Action Fabric capability. Direct general-workspace plans are also marked so Observation
 * can verify them independently. This is capability composition, not fake staffing for a tool name.
 */
public final class GeneralActionComposingExecutionPlanProposalService implements ExecutionPlanProposalService {
    public static final String GENERAL_RUNTIME_MARKER = "general-action-runtime:" + GeneralWorkspaceAutonomousCapability.CAPABILITY;
    private static final String REPOSITORY_AUDIT_READ = "repository.audit.read";
    private static final String CROSS_REPOSITORY_AUDIT_ANALYSIS = "cross-repository-audit-analysis";
    private static final String RECOVERY_PROBE_READ = "autonomy.recovery.probe.read";
    private static final String UNAVAILABLE_PREFIX = "unavailable:";

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
        plan = collapseExplicitRecoveryComposite(request, availableCapabilities, plan);
        plan = removeInvalidCrossRepositoryAuditJoins(plan);
        if (plan.isEmpty()) return plan;
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

    /**
     * The bounded recovery probe is itself a composite capability whose result is independently
     * verified by Observation. If the Human explicitly requests that exact capability against its
     * recovery target, additional planner-created reporting/workspace steps are scope expansion and
     * must not enter the Work Graph.
     */
    static List<ExecutionWorkSpec> collapseExplicitRecoveryComposite(
            NormalizedRequest request,
            List<String> availableCapabilities,
            List<ExecutionWorkSpec> plan) {
        if (request == null || plan == null || plan.isEmpty()) return plan;
        boolean available = availableCapabilities != null && availableCapabilities.stream()
                .filter(Objects::nonNull).map(String::trim)
                .anyMatch(value -> value.equals(RECOVERY_PROBE_READ)
                        || value.startsWith(RECOVERY_PROBE_READ + " ")
                        || value.startsWith(RECOVERY_PROBE_READ + "|"));
        if (!available) return plan;
        String semantic = (request.objective() + " " + request.target()).toLowerCase(Locale.ROOT);
        if (!semantic.contains(RECOVERY_PROBE_READ) || !request.target().startsWith("p10-recovery://")) return plan;
        List<ExecutionWorkSpec> probes = plan.stream()
                .filter(step -> RECOVERY_PROBE_READ.equals(step.requiredCapability()))
                .toList();
        if (probes.size() != 1) return plan;
        ExecutionWorkSpec probe = probes.getFirst();
        if (probe.consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) return plan;
        return List.of(new ExecutionWorkSpec(
                probe.stepId(), probe.objective(), probe.target(), probe.requiredCapability(), List.of(),
                probe.consequence(), probe.acceptanceCriteria(), probe.evidenceRequirements()));
    }

    /**
     * A cross-repository analysis is a typed join over repository audit evidence. Frontier planning
     * may propose the capability with syntactically valid but semantically unrelated dependencies;
     * such a join must not be allowed into the Work Graph. When an invalid join is removed, any
     * downstream dependency is transparently rewired to the join's original prerequisites.
     */
    static List<ExecutionWorkSpec> removeInvalidCrossRepositoryAuditJoins(List<ExecutionWorkSpec> plan) {
        Map<String, ExecutionWorkSpec> byId = new LinkedHashMap<>();
        for (ExecutionWorkSpec step : plan) byId.put(step.stepId(), step);

        Map<String, List<String>> removed = new LinkedHashMap<>();
        for (ExecutionWorkSpec step : plan) {
            if (!CROSS_REPOSITORY_AUDIT_ANALYSIS.equals(step.requiredCapability())) continue;
            boolean valid = step.dependsOn().size() >= 2 && step.dependsOn().stream().allMatch(dependency -> {
                ExecutionWorkSpec source = byId.get(dependency);
                return source != null && REPOSITORY_AUDIT_READ.equals(source.requiredCapability());
            });
            if (!valid) removed.put(step.stepId(), step.dependsOn());
        }
        if (removed.isEmpty()) return plan;

        List<ExecutionWorkSpec> sanitized = new ArrayList<>();
        for (ExecutionWorkSpec step : plan) {
            if (removed.containsKey(step.stepId())) continue;
            LinkedHashSet<String> dependencies = new LinkedHashSet<>();
            for (String dependency : step.dependsOn()) {
                expandRemovedDependency(dependency, removed, dependencies, new LinkedHashSet<>());
            }
            sanitized.add(new ExecutionWorkSpec(
                    step.stepId(), step.objective(), step.target(), step.requiredCapability(),
                    List.copyOf(dependencies), step.consequence(), step.acceptanceCriteria(), step.evidenceRequirements()));
        }
        return List.copyOf(sanitized);
    }

    private static void expandRemovedDependency(String dependency,
                                                Map<String, List<String>> removed,
                                                LinkedHashSet<String> output,
                                                Set<String> visiting) {
        List<String> replacement = removed.get(dependency);
        if (replacement == null) {
            output.add(dependency);
            return;
        }
        if (!visiting.add(dependency)) throw new IllegalStateException("cyclic invalid cross-repository join: " + dependency);
        for (String nested : replacement) expandRemovedDependency(nested, removed, output, visiting);
        visiting.remove(dependency);
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
        String capability = step.requiredCapability().toLowerCase(Locale.ROOT).trim();
        if (capability.startsWith(UNAVAILABLE_PREFIX)) {
            capability = capability.substring(UNAVAILABLE_PREFIX.length()).trim();
        }
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
