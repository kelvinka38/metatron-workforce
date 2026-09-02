package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-backed general Cognitive Worker brain. Action authority remains entirely in ActionFabric. */
public final class GeneralCognitiveWorkerBrain implements CognitiveWorkerRuntime.Brain {
    private final LlmProviderRouter router;
    private final LlmProvider provider;
    private final String model;
    private final ObjectMapper json;
    private final List<String> evidence = new ArrayList<>();

    public GeneralCognitiveWorkerBrain(LlmProviderRouter router, LlmProvider provider, String model, ObjectMapper json) {
        this.router = Objects.requireNonNull(router, "router");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.model = require(model, "model");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public CognitiveWorkerRuntime.Thought think(CognitiveWorkerRuntime.CognitiveContext context) {
        String system = """
                You are the action-selection brain for a governed Metatron Cognitive Worker.
                You have no authority to execute outside the supplied action catalog.
                Select exactly one next action that advances the actual Work using current observations.
                Do not invent action names. Do not claim completion in this response.
                Inputs must be concrete strings. For list arguments use a JSON array encoded as a string in argsJson/tasksJson.
                Return ONLY JSON: {"actionRef":"...","inputs":{"key":"value"},"rationale":"short operational reason"}.
                """;
        LlmResponse response = complete(system, contextPrompt(context));
        Map<String, Object> parsed = parseObject(response.text());
        String actionRef = text(parsed.get("actionRef"), "actionRef");
        String rationale = text(parsed.get("rationale"), "rationale");
        Map<String, String> inputs = stringMap(parsed.get("inputs"));
        return new CognitiveWorkerRuntime.Thought(actionRef, inputs, rationale);
    }

    @Override
    public CognitiveWorkerRuntime.Reflection reflect(CognitiveWorkerRuntime.CognitiveContext context,
                                                      ActionFabric.ActionObservation observation) {
        String system = """
                You are the reflection brain for a governed Metatron Cognitive Worker.
                Decide from the actual Work, acceptance criteria, evidence requirements and observed action result.
                COMPLETE only when the required outcome is genuinely evidenced. CONTINUE if another action can advance it.
                FAILED only when the observed state makes bounded recovery impossible.
                Return ONLY JSON: {"decision":"CONTINUE|COMPLETE|FAILED","summary":"short evidence-based reason"}.
                """;
        String user = contextPrompt(context) + "\nLATEST_OBSERVATION=" + write(Map.of(
                "actionRef", observation.actionRef(),
                "success", observation.success(),
                "summary", observation.summary(),
                "outputs", observation.outputs(),
                "evidence", observation.evidenceReferences()));
        LlmResponse response = complete(system, user);
        Map<String, Object> parsed = parseObject(response.text());
        String decision = text(parsed.get("decision"), "decision").toUpperCase(java.util.Locale.ROOT);
        String summary = text(parsed.get("summary"), "summary");
        return switch (decision) {
            case "CONTINUE" -> CognitiveWorkerRuntime.Reflection.continueWith(summary);
            case "COMPLETE" -> CognitiveWorkerRuntime.Reflection.complete(summary);
            case "FAILED" -> CognitiveWorkerRuntime.Reflection.failed(summary);
            default -> throw new IllegalStateException("invalid cognitive reflection decision: " + decision);
        };
    }

    public List<String> evidenceReferences() {
        return List.copyOf(evidence);
    }

    private LlmResponse complete(String system, String user) {
        LlmResponse response = router.complete(new LlmRequest(provider, model, system, user));
        evidence.add("cognitive-provider:" + response.provider()
                + ":model=" + response.model()
                + ":request=" + clean(response.providerRequestReference()));
        return response;
    }

    private String contextPrompt(CognitiveWorkerRuntime.CognitiveContext context) {
        List<Map<String, Object>> catalog = context.catalog().stream().map(entry -> Map.<String, Object>of(
                "actionRef", entry.actionRef(), "consequence", entry.consequence().name())).toList();
        List<Map<String, Object>> history = context.history().stream()
                .skip(Math.max(0, context.history().size() - 10L))
                .map(cycle -> Map.<String, Object>of(
                        "cycle", cycle.index(),
                        "actionRef", cycle.thought().actionRef(),
                        "inputs", cycle.thought().inputs(),
                        "observationSuccess", cycle.observation().success(),
                        "observationSummary", cycle.observation().summary(),
                        "observationOutputs", cycle.observation().outputs(),
                        "reflection", cycle.reflection().decision().name(),
                        "reflectionSummary", cycle.reflection().summary()))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("objectiveId", context.objectiveId());
        payload.put("workerId", context.workerId());
        payload.put("assignmentReference", context.assignmentReference());
        payload.put("work", Map.of(
                "stepId", context.workSpec().stepId(),
                "objective", context.workSpec().objective(),
                "target", context.workSpec().target(),
                "requiredCapability", context.workSpec().requiredCapability(),
                "consequence", context.workSpec().consequence().name(),
                "acceptanceCriteria", context.workSpec().acceptanceCriteria(),
                "evidenceRequirements", context.workSpec().evidenceRequirements()));
        payload.put("availableActions", catalog);
        payload.put("memory", context.memory());
        payload.put("recentCycles", history);
        return write(payload);
    }

    private Map<String, Object> parseObject(String raw) {
        String value = raw == null ? "" : raw.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("cognitive provider returned no JSON object");
        try {
            return json.readValue(value.substring(start, end + 1), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("invalid cognitive provider JSON", e);
        }
    }

    private Map<String, String> stringMap(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalStateException("cognitive inputs must be an object");
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            String k = String.valueOf(key);
            if (value instanceof String text) out.put(k, text);
            else out.put(k, write(value));
        });
        return Map.copyOf(out);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize cognitive context", e); }
    }

    private static String text(Object value, String field) {
        String out = value == null ? "" : String.valueOf(value).trim();
        if (out.isBlank()) throw new IllegalStateException("cognitive response missing " + field);
        return out;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
