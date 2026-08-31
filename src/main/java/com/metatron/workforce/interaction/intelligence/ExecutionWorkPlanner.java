package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Post-semantic, post-Case institutional execution work planner. */
public final class ExecutionWorkPlanner implements ExecutionPlanProposalService {
    private static final String REPOSITORY_PR_PROPOSE = "repository.pr.propose";

    private static final String SYSTEM = """
            You are Metatron's institutional execution work planner.
            You receive an already normalized Human request and an already-created Intelligence Case reference.
            Do NOT reinterpret raw Human language. Do NOT manufacture authority, authorization, assignment, execution evidence, Observation or completion.

            For an EXECUTION request, return ONLY one JSON object:
            {
              "execution_work_plan": [
                {
                  "step_id": "...",
                  "objective": "...",
                  "target": "...",
                  "required_capability": "...",
                  "depends_on": [],
                  "consequence": "READ_ONLY|MUTATING",
                  "acceptance_criteria": ["criterion stated as an observable condition"],
                  "evidence_requirements": ["evidence needed to independently verify that criterion"]
                }
              ]
            }

            Rules:
            - Decompose only the normalized objective actually requested.
            - Every material Work step MUST state at least one observable acceptance criterion and at least one evidence requirement.
            - Acceptance criteria describe the desired observable outcome; they are not execution claims.
            - Evidence requirements describe what an independent Observation must inspect or obtain; never fabricate evidence refs.
            - Use exact refs from AVAILABLE EXECUTION CAPABILITIES when a capability can perform the step.
            - Prefer one available bounded/composite capability over inventing lower-level effects that are not independently available.
            - `repository.pr.propose` is the bounded governed mutation capability for the approved Autonomy Closure repair: it performs the approved file repair on a branch and opens the pull request. It never merges. When that capability satisfies a repair-and-open-PR objective, do NOT invent a separate `repository.content.write` step.
            - If no capability can perform a step, use UNAVAILABLE:<short-semantic-capability-need>.
            - A READ_ONLY capability cannot satisfy MUTATING work.
            - Preserve explicit prohibitions and constraints.
            - Normalize an unambiguous GitHub repository target to owner/repo when needed.
            - Work planning is not authority, authorization, assignment, execution, Observation or evidence.
            - Dependencies may only refer to earlier steps.
            """;

    private final LlmProviderRouter router;
    private final Function<LlmProvider, String> modelSelector;
    private final List<LlmProvider> providers;
    private final ObjectMapper mapper;

    public ExecutionWorkPlanner(LlmProviderRouter router,
                                Function<LlmProvider, String> modelSelector,
                                List<LlmProvider> configuredProviders,
                                ObjectMapper mapper) {
        this.router = Objects.requireNonNull(router, "router");
        this.modelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        this.providers = configuredProviders.stream().distinct().toList();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public List<ExecutionWorkSpec> propose(String caseId, NormalizedRequest normalized,
                                           List<String> availableExecutionCapabilities) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(availableExecutionCapabilities, "availableExecutionCapabilities");
        if (normalized.mode() != IntelligenceMode.EXECUTION) return List.of();
        if (providers.isEmpty()) throw new IllegalStateException("execution_planning_provider_required");

        String input = "INTELLIGENCE CASE REF:\n" + caseId
                + "\n\nNORMALIZED REQUEST (structured; already semantically interpreted):\n"
                + render(normalized)
                + "\n\nAVAILABLE EXECUTION CAPABILITIES (inventory only; never authority):\n"
                + (availableExecutionCapabilities.isEmpty() ? "NONE" : String.join("\n", availableExecutionCapabilities));

        List<LlmProvider> orderedProviders = providersFor(normalized);
        List<RuntimeException> failures = new ArrayList<>();
        for (LlmProvider provider : orderedProviders) {
            try {
                LlmResponse response = router.complete(new LlmRequest(provider, modelSelector.apply(provider), SYSTEM, input));
                List<ExecutionWorkSpec> plan = parse(response);
                plan = reconcileCompositeCapabilities(normalized, availableExecutionCapabilities, plan);
                validate(plan);
                if (plan.isEmpty()) throw new IllegalStateException("execution planner returned empty plan");
                return plan;
            } catch (RuntimeException failure) {
                failures.add(new IllegalStateException("execution planning provider failed: " + provider + ": " + failure.getMessage(), failure));
            }
        }
        IllegalStateException all = new IllegalStateException("all execution planning providers failed: " + orderedProviders);
        failures.forEach(all::addSuppressed);
        throw all;
    }

    public List<ExecutionWorkSpec> plan(String caseId, NormalizedRequest normalized,
                                        List<String> availableExecutionCapabilities) {
        return propose(caseId, normalized, availableExecutionCapabilities);
    }

    private List<LlmProvider> providersFor(NormalizedRequest normalized) {
        LlmProvider explicit = normalized.explicitlyRequestedProvider();
        if (explicit != null) return providers.contains(explicit) ? List.of(explicit) : List.of();
        return AdaptiveProviderRoutingPolicy.rankConfiguredProviders(providers, router.telemetry());
    }

    private static String render(NormalizedRequest request) {
        return "objective=" + request.objective()
                + "\ntarget=" + request.target()
                + "\nconstraints=" + request.constraints()
                + "\nexplicit_prohibitions=" + request.explicitProhibitions()
                + "\nrequested_output=" + request.requestedOutput()
                + "\nmode=" + request.mode()
                + "\nanalytical_protocols=" + request.analyticalProtocols()
                + "\ntemporal_context=" + request.temporalContext();
    }

    private List<ExecutionWorkSpec> parse(LlmResponse response) {
        try {
            JsonNode root = mapper.readTree(unwrapJson(response.text()));
            JsonNode node = root.path("execution_work_plan");
            if (!node.isArray()) return List.of();
            List<ExecutionWorkSpec> values = new ArrayList<>();
            node.forEach(item -> {
                if (!item.isObject()) return;
                String stepId = optionalText(item, "step_id");
                String objective = optionalText(item, "objective");
                String target = optionalText(item, "target");
                String capability = optionalText(item, "required_capability");
                List<String> dependsOn = textArray(item, "depends_on");
                String consequence = optionalText(item, "consequence");
                List<String> criteria = textArray(item, "acceptance_criteria");
                List<String> evidenceRequirements = textArray(item, "evidence_requirements");
                if (stepId.isBlank() || objective.isBlank() || capability.isBlank() || consequence.isBlank()) return;
                values.add(new ExecutionWorkSpec(stepId, objective, target, capability, dependsOn,
                        enumValue(ExecutionWorkSpec.Consequence.class, consequence), criteria, evidenceRequirements));
            });
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid execution plan from " + response.provider(), failure);
        }
    }

    /**
     * Frontier models occasionally decompose an already bounded capability into an unavailable
     * lower-level effect followed by the real composite capability. That creates a false staffing
     * gap even though Workforce has an executable governed adapter. Reconcile only the narrow,
     * policy-known PR pattern: one unavailable repository write chain ending in the existing
     * repository.pr.propose capability. The composite keeps the first dependency fence and unions
     * all criterion/evidence requirements; it does not manufacture authority or execution evidence.
     */
    private static List<ExecutionWorkSpec> reconcileCompositeCapabilities(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities,
            List<ExecutionWorkSpec> plan) {
        if (!availableExecutionCapabilities.contains(REPOSITORY_PR_PROPOSE)
                || !requestsRepositoryPullRequest(normalized)
                || plan.isEmpty()) return plan;

        int unavailableWriteIndex = -1;
        int pullRequestIndex = -1;
        for (int i = 0; i < plan.size(); i++) {
            ExecutionWorkSpec step = plan.get(i);
            if (step.consequence() == ExecutionWorkSpec.Consequence.MUTATING
                    && isUnavailableRepositoryWrite(step.requiredCapability())) {
                if (unavailableWriteIndex >= 0) return plan;
                unavailableWriteIndex = i;
            }
            if (REPOSITORY_PR_PROPOSE.equals(step.requiredCapability())) {
                if (pullRequestIndex >= 0) return plan;
                pullRequestIndex = i;
            }
        }
        if (unavailableWriteIndex < 0 || pullRequestIndex <= unavailableWriteIndex) return plan;

        String expectedTarget = normalizeTarget(normalized.target());
        for (int i = unavailableWriteIndex; i <= pullRequestIndex; i++) {
            String target = normalizeTarget(plan.get(i).target());
            if (!expectedTarget.isBlank() && !target.isBlank() && !expectedTarget.equals(target)) return plan;
            if (i > unavailableWriteIndex) {
                String previousStep = plan.get(i - 1).stepId();
                if (!plan.get(i).dependsOn().contains(previousStep)) return plan;
            }
        }

        ExecutionWorkSpec first = plan.get(unavailableWriteIndex);
        LinkedHashSet<String> criteria = new LinkedHashSet<>();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        Set<String> removedStepIds = new LinkedHashSet<>();
        for (int i = unavailableWriteIndex; i <= pullRequestIndex; i++) {
            ExecutionWorkSpec step = plan.get(i);
            removedStepIds.add(step.stepId());
            criteria.addAll(step.acceptanceCriteria());
            evidence.addAll(step.evidenceRequirements());
        }

        ExecutionWorkSpec composite = new ExecutionWorkSpec(
                first.stepId(),
                normalized.objective(),
                normalized.target().isBlank() ? first.target() : normalized.target(),
                REPOSITORY_PR_PROPOSE,
                first.dependsOn(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.copyOf(criteria),
                List.copyOf(evidence));

        List<ExecutionWorkSpec> reconciled = new ArrayList<>();
        reconciled.addAll(plan.subList(0, unavailableWriteIndex));
        reconciled.add(composite);
        for (int i = pullRequestIndex + 1; i < plan.size(); i++) {
            ExecutionWorkSpec step = plan.get(i);
            List<String> dependencies = step.dependsOn().stream()
                    .map(dependency -> removedStepIds.contains(dependency) ? composite.stepId() : dependency)
                    .distinct().toList();
            reconciled.add(new ExecutionWorkSpec(
                    step.stepId(), step.objective(), step.target(), step.requiredCapability(), dependencies,
                    step.consequence(), step.acceptanceCriteria(), step.evidenceRequirements()));
        }
        return List.copyOf(reconciled);
    }

    private static boolean requestsRepositoryPullRequest(NormalizedRequest normalized) {
        String objective = normalized.objective().toLowerCase(Locale.ROOT);
        if (!objective.contains("pull request")) return false;
        String target = normalizeTarget(normalized.target());
        return target.contains("/");
    }

    private static boolean isUnavailableRepositoryWrite(String capability) {
        String value = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("unavailable:")) return false;
        String need = value.substring("unavailable:".length());
        return need.contains("repository") && (need.contains("write") || need.contains("content"));
    }

    private static String normalizeTarget(String target) {
        if (target == null) return "";
        String value = target.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        return value.replaceAll("\\.git$", "");
    }

    private static void validate(List<ExecutionWorkSpec> plan) {
        List<String> seen = new ArrayList<>();
        for (ExecutionWorkSpec step : plan) {
            if (seen.contains(step.stepId())) throw new IllegalStateException("duplicate execution step id: " + step.stepId());
            if (!step.verifiable()) throw new IllegalStateException("execution step lacks criterion-level verification requirements: " + step.stepId());
            for (String dependency : step.dependsOn()) {
                if (!seen.contains(dependency)) throw new IllegalStateException("execution step dependency must reference an earlier step: " + dependency);
            }
            seen.add(step.stepId());
        }
    }

    private static String unwrapJson(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("execution planning response is not JSON");
        return value.substring(start, end + 1);
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private static List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().trim()); });
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
