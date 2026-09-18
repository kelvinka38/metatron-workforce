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
            "git", "build", "test", "code", "source", "patch", "artifact", "compile",
            "research", "web", "internet", "search", "evidence", "publication", "paper",
            "report", "standard", "regulator", "regulatory", "recovery");

    private final ExecutionPlanProposalService delegate;

    public GeneralActionComposingExecutionPlanProposalService(ExecutionPlanProposalService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<ExecutionWorkSpec> propose(String caseId, NormalizedRequest request, List<String> availableCapabilities) {
        List<ExecutionWorkSpec> deterministicExplicitGeneral =
                deterministicExplicitGeneralWorkspaceObjective(request, availableCapabilities);
        if (!deterministicExplicitGeneral.isEmpty() && request.explicitlyRequestedProvider() == null) {
            return deterministicExplicitGeneral;
        }

        List<ExecutionWorkSpec> plan = delegate.propose(caseId, request, availableCapabilities);
        if (plan == null || plan.isEmpty()) return plan;
        plan = collapseExplicitRecoveryComposite(request, availableCapabilities, plan);
        plan = normalizeRecoveryProbeTargets(plan);
        plan = collapseExplicitGeneralWorkspaceObjective(request, availableCapabilities, plan);
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
     * Explicit execution.general.workspace is already a complete governed capability-routing
     * decision. Workforce does not need a frontier provider merely to restate that one Work item.
     * The Cognitive Worker owns the detailed inspect/change/test/commit/publish action sequence.
     */
    static List<ExecutionWorkSpec> deterministicExplicitGeneralWorkspaceObjective(
            NormalizedRequest request,
            List<String> availableCapabilities) {
        if (request == null) return List.of();
        boolean generalAvailable = availableCapabilities != null && availableCapabilities.stream()
                .filter(Objects::nonNull).map(String::trim)
                .anyMatch(GeneralWorkspaceAutonomousCapability.CAPABILITY::equals);
        if (!generalAvailable) return List.of();

        String semantic = explicitRequestSemantic(request);
        String lower = semantic.toLowerCase(Locale.ROOT);
        if (!lower.contains(GeneralWorkspaceAutonomousCapability.CAPABILITY.toLowerCase(Locale.ROOT))) {
            return List.of();
        }

        boolean mutating = lower.contains("mutating")
                || lower.contains("fix") || lower.contains("repair")
                || lower.contains("patch") || lower.contains("write")
                || lower.contains("modify") || lower.contains("change")
                || lower.contains("commit") || lower.contains("pull request")
                || lower.contains("publish") || lower.contains("push")
                || lower.contains("delete") || lower.contains("deploy");
        ExecutionWorkSpec.Consequence consequence = mutating
                ? ExecutionWorkSpec.Consequence.MUTATING
                : ExecutionWorkSpec.Consequence.READ_ONLY;

        List<String> acceptance = new ArrayList<>();
        addDistinct(acceptance, "explicit execution.general.workspace Objective completes without capability escape");
        if (consequence == ExecutionWorkSpec.Consequence.MUTATING) {
            addDistinct(acceptance, "requested workspace source change is present in the committed work product");
        }
        if (lower.contains("test")) {
            addDistinct(acceptance, "repository tests pass after the requested workspace change");
        }
        if (lower.contains("pull request") || lower.contains("open pr")
                || lower.contains("proposal branch") || lower.contains("publish") && lower.contains("github")) {
            addDistinct(acceptance, "reviewable pull request exists for the committed Objective work product");
        }
        if (lower.contains("do not merge") || request.explicitProhibitions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains("merge"))) {
            addDistinct(acceptance, "remote proposal remains unmerged");
        }
        if (acceptance.size() == 1) {
            addDistinct(acceptance, "requested governed workspace outcome is independently observable");
        }

        List<String> evidence = new ArrayList<>();
        addDistinct(evidence, GENERAL_RUNTIME_MARKER);
        addDistinct(evidence, "requested-capability:" + GeneralWorkspaceAutonomousCapability.CAPABILITY);
        addDistinct(evidence, "general Action Fabric action journal");
        if (lower.contains("test")) addDistinct(evidence, "governed test action evidence");
        if (lower.contains("pull request") || lower.contains("proposal branch")
                || lower.contains("publish") && lower.contains("github")) {
            addDistinct(evidence, "fresh authoritative GitHub API Observation");
        }

        ExecutionWorkSpec base = new ExecutionWorkSpec(
                "explicit-general-workspace",
                semantic,
                request.target(),
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                consequence,
                acceptance,
                evidence);
        return GeneralWorkspacePhasePlanner.phase(base);
    }

    /**
     * When the Human explicitly selects the general workspace runtime, that is a capability-routing
     * constraint for the whole Objective, not permission for the planner to replace later work with a
     * narrower special capability. Collapse planner scope expansion back into one general Work item so
     * the Cognitive Worker owns the complete requested sequence through its governed Action Fabric.
     */
    static List<ExecutionWorkSpec> collapseExplicitGeneralWorkspaceObjective(
            NormalizedRequest request,
            List<String> availableCapabilities,
            List<ExecutionWorkSpec> plan) {
        if (request == null || plan == null || plan.isEmpty()) return plan;
        boolean generalAvailable = availableCapabilities != null && availableCapabilities.stream()
                .filter(Objects::nonNull).map(String::trim)
                .anyMatch(GeneralWorkspaceAutonomousCapability.CAPABILITY::equals);
        if (!generalAvailable) return plan;

        String semantic = explicitRequestSemantic(request);
        if (!semantic.toLowerCase(Locale.ROOT).contains(
                GeneralWorkspaceAutonomousCapability.CAPABILITY.toLowerCase(Locale.ROOT))) {
            return plan;
        }

        ExecutionWorkSpec.Consequence consequence = plan.stream()
                .anyMatch(step -> step.consequence() == ExecutionWorkSpec.Consequence.MUTATING)
                || semantic.toLowerCase(Locale.ROOT).contains("mutating")
                ? ExecutionWorkSpec.Consequence.MUTATING
                : ExecutionWorkSpec.Consequence.READ_ONLY;

        String lower = semantic.toLowerCase(Locale.ROOT);
        List<String> acceptance = new ArrayList<>();
        addDistinct(acceptance, "explicit execution.general.workspace Objective completes without capability escape");
        if (consequence == ExecutionWorkSpec.Consequence.MUTATING) {
            addDistinct(acceptance, "requested workspace source change is present in the committed work product");
        }
        if (lower.contains("test")) {
            addDistinct(acceptance, "repository tests pass after the requested workspace change");
        }
        if (lower.contains("pull request") || lower.contains("open pr")
                || lower.contains("proposal branch") || lower.contains("publish") && lower.contains("github")) {
            addDistinct(acceptance, "reviewable pull request exists for the committed Objective work product");
        }
        if (lower.contains("do not merge") || request.explicitProhibitions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains("merge"))) {
            addDistinct(acceptance, "remote proposal remains unmerged");
        }

        List<String> evidence = new ArrayList<>();
        addDistinct(evidence, GENERAL_RUNTIME_MARKER);
        addDistinct(evidence, "requested-capability:" + GeneralWorkspaceAutonomousCapability.CAPABILITY);
        addDistinct(evidence, "general Action Fabric action journal");
        if (lower.contains("test")) addDistinct(evidence, "governed test action evidence");
        if (lower.contains("pull request") || lower.contains("proposal branch")
                || lower.contains("publish") && lower.contains("github")) {
            addDistinct(evidence, "fresh authoritative GitHub API Observation");
        }

        String stepId = plan.stream()
                .filter(step -> GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(step.requiredCapability()))
                .map(ExecutionWorkSpec::stepId)
                .findFirst()
                .orElse(plan.getFirst().stepId());

        ExecutionWorkSpec base = new ExecutionWorkSpec(
                stepId,
                semantic,
                request.target(),
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                consequence,
                acceptance,
                evidence);
        return GeneralWorkspacePhasePlanner.phase(base);
    }

    private static String explicitRequestSemantic(NormalizedRequest request) {
        StringBuilder out = new StringBuilder(request.objective().trim());
        if (!request.constraints().isEmpty()) {
            out.append("\nConstraints: ").append(String.join("; ", request.constraints()));
        }
        if (!request.explicitProhibitions().isEmpty()) {
            out.append("\nProhibitions: ").append(String.join("; ", request.explicitProhibitions()));
        }
        if (!request.requestedOutput().isBlank()) {
            out.append("\nRequested output: ").append(request.requestedOutput().trim());
        }
        return out.toString();
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

    /**
     * Root-cause fix (2026-09-15): AutonomyRecoveryProbeCapability requires an exact
     * "p10-recovery://{transient-timeout|restart-window|observation-retry}/..." target, but nothing
     * upstream forces the frontier planner to produce that exact machine-parseable URI when a Human's
     * free-text request only vaguely asks to "verify autonomy/self-healing" -- observed live: a real
     * Objective got stuck permanently BLOCKED because the planner picked this capability with a target
     * that failed the format check only at execution time, with no path to ever recover on its own.
     * Rather than let a malformed target reach execution and fail loudly forever, normalize it here at
     * plan time to the safest of the three modes (observation-retry: verifies the retry path only, does
     * not inject a real timeout or sleep-then-crash like the other two), keyed by stepId so it stays
     * traceable to the original planned step.
     */
    static List<ExecutionWorkSpec> normalizeRecoveryProbeTargets(List<ExecutionWorkSpec> plan) {
        if (plan == null || plan.isEmpty()) return plan;
        boolean needsNormalization = plan.stream().anyMatch(step ->
                RECOVERY_PROBE_READ.equals(step.requiredCapability())
                        && (step.target() == null || !step.target().startsWith("p10-recovery://")));
        if (!needsNormalization) return plan;
        List<ExecutionWorkSpec> normalized = new ArrayList<>(plan.size());
        for (ExecutionWorkSpec step : plan) {
            if (!RECOVERY_PROBE_READ.equals(step.requiredCapability())
                    || (step.target() != null && step.target().startsWith("p10-recovery://"))) {
                normalized.add(step);
                continue;
            }
            normalized.add(new ExecutionWorkSpec(
                    step.stepId(), step.objective(), "p10-recovery://observation-retry/" + step.stepId(),
                    step.requiredCapability(), step.dependsOn(), step.consequence(),
                    step.acceptanceCriteria(), step.evidenceRequirements()));
        }
        return List.copyOf(normalized);
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
        if (capability.contains("recovery") || capability.contains("autonomous-recovery")) return true;
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
