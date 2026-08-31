package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Post-semantic, post-Case institutional execution work planner. */
public final class ExecutionWorkPlanner implements ExecutionPlanProposalService {
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
            validate(values);
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid execution plan from " + response.provider(), failure);
        }
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
