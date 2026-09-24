package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Canonical action-input contract shared by cognition prompts and ActionFabric enforcement. */
public final class ActionContractCatalog {
    public enum InputType { STRING, INTEGER, JSON_STRING_ARRAY }

    public record InputSpec(
            boolean required,
            boolean allowEmpty,
            InputType type,
            Integer minInteger,
            Integer maxInteger,
            String description) {
        public InputSpec {
            Objects.requireNonNull(type, "type");
            description = description == null ? "" : description.trim();
        }
    }

    public record Contract(boolean strictInputs, Map<String, InputSpec> inputs, String purpose) {
        public Contract {
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            purpose = purpose == null ? "" : purpose.trim();
        }

        public static Contract permissive() {
            return new Contract(false, Map.of(), "");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Contract> CONTRACTS = Map.ofEntries(
            Map.entry(GeneralWebResearchAction.ACTION_REF, contract(
                    "retrieve current external evidence through the governed web search ToolFabric; read-only and source-attributed",
                    Map.of("query", requiredString("required external research/search requirement")))),
            Map.entry("workspace.repository.materialize", contract(
                    "materialize an immutable repository snapshot into this Objective workspace",
                    Map.of(
                            "repository", requiredString("required owner/name"),
                            "ref", optionalString("optional branch/tag/SHA; default main"),
                            "createIfMissing", optionalString(
                                    "optional \"true\" -- only ever set by the brain for a planner-derived "
                                            + "fresh-new-application destination, never an explicitly-named repository")))),
            Map.entry("workspace.file.read", contract(
                    "read one UTF-8 workspace file",
                    Map.of("path", requiredString("required workspace-relative path")))),
            Map.entry("workspace.file.list", contract(
                    "list bounded workspace paths",
                    Map.of("path", optionalString("optional workspace-relative directory; empty means root")))),
            Map.entry("workspace.file.search", contract(
                    "search source text across the bounded Objective workspace",
                    Map.of(
                            "query", requiredString("required exact text fragment"),
                            "path", optionalString("optional workspace-relative directory"),
                            "maxMatches", optionalInteger(1, 500, "optional integer 1..500; default 100")))),
            Map.entry("workspace.file.patch", contract(
                    "apply one bounded exact patch after inspection",
                    Map.of(
                            "path", requiredString("required workspace-relative file"),
                            "oldText", requiredString("required exact existing text"),
                            "newText", optionalString("replacement text; empty allowed"),
                            "expectedOccurrences", optionalInteger(1, 100, "optional integer 1..100; default 1")))),
            Map.entry("workspace.file.write", contract(
                    "create or replace one workspace file",
                    Map.of(
                            "path", requiredString("required workspace-relative path"),
                            "content", optionalString("file content; empty allowed")))),
            Map.entry("workspace.project.prepare", contract(
                    "inspect carried source and deterministically create the minimal supported project scaffold",
                    Map.of())),
            Map.entry("workspace.dependencies.install", contract(
                    "detect supported dependency metadata and install dependencies in the isolated Objective workspace",
                    Map.of("workingDirectory", optionalString("optional workspace-relative project directory; empty means root")))),
            Map.entry("workspace.process.run", contract(
                    "run one allowlisted process in the isolated Objective sandbox",
                    Map.of(
                            "executable", requiredString("required executable from runtime allowlist"),
                            "argsJson", requiredJsonArray("required JSON string array of arguments"),
                            "workingDirectory", optionalString("optional workspace-relative working directory")))),
            Map.entry("workspace.shell.run", contract(
                    "run one constrained allowlisted command in the isolated Objective sandbox",
                    Map.of(
                            "command", requiredString("required constrained command"),
                            "workingDirectory", optionalString("optional workspace-relative working directory")))),
            Map.entry("workspace.git.status", contract(
                    "read-only inspection of local Git status, immutable HEAD SHA and bounded recent commit log",
                    Map.of())),
            Map.entry("workspace.git.diff", contract(
                    "inspect uncommitted local Git diff",
                    Map.of())),
            Map.entry("workspace.git.run", contract(
                    "run a local Git operation; no remote credential is exposed to the sandbox",
                    Map.of("argsJson", requiredJsonArray("required JSON string array of git arguments")))),
            Map.entry("workspace.github.pr.publish", contract(
                    "publish the committed Objective-workspace delta as a reviewable unmerged GitHub proposal",
                    Map.of(
                            "title", optionalString("optional pull-request title"),
                            "body", optionalString("optional pull-request body")))),
            Map.entry("workspace.build.run", contract(
                    "detect the project build system and run its build in the isolated sandbox",
                    Map.of(
                            "tasksJson", optionalJsonArray("optional JSON string array of build tasks/scripts/paths"),
                            "workingDirectory", optionalString("optional workspace-relative project directory")))),
            Map.entry("workspace.test.run", contract(
                    "detect the project test system and run tests in the isolated sandbox",
                    Map.of(
                            "tasksJson", optionalJsonArray("optional JSON string array of test tasks/scripts/pytest arguments"),
                            "workingDirectory", optionalString("optional workspace-relative project directory"))))
    );

    private ActionContractCatalog() {}

    public static Contract contractFor(String actionRef) {
        if (actionRef == null || actionRef.isBlank()) return Contract.permissive();
        return CONTRACTS.getOrDefault(actionRef.trim(), Contract.permissive());
    }

    public static Map<String, Object> promptContract(String actionRef) {
        Contract contract = contractFor(actionRef);
        if (!contract.strictInputs()) return Map.of();
        Map<String, String> inputs = new LinkedHashMap<>();
        contract.inputs().forEach((key, spec) -> inputs.put(key, spec.description()));
        return Map.of("inputs", Map.copyOf(inputs), "purpose", contract.purpose());
    }

    /**
     * Repairs the one provider-output shape mismatch that deterministically fails validation: a
     * JSON_STRING_ARRAY input written as a bare string ("build") or a JSON array of non-strings. Production
     * 2026-09-24 (case-9371b421 VERIFY) and 2026-09-22 both reached the repeated-failure circuit breaker only
     * because the model wrote tasksJson/argsJson as a plain string. A bare string becomes a one-element
     * array, array items become strings, and a blank optional array is dropped (its default applies).
     * Everything else is left untouched for {@link #validate} to judge; this never adds keys or authority.
     */
    public static Map<String, String> normalizeProviderInputs(String actionRef, Map<String, String> suppliedInputs) {
        Contract contract = contractFor(actionRef);
        if (!contract.strictInputs() || suppliedInputs == null || suppliedInputs.isEmpty()) {
            return suppliedInputs == null ? Map.of() : suppliedInputs;
        }
        Map<String, String> out = new LinkedHashMap<>(suppliedInputs);
        for (Map.Entry<String, InputSpec> entry : contract.inputs().entrySet()) {
            String key = entry.getKey();
            if (entry.getValue().type() != InputType.JSON_STRING_ARRAY || !out.containsKey(key)) continue;
            String value = out.get(key);
            if (value == null) continue;
            if (value.isBlank()) {
                if (!entry.getValue().required()) out.remove(key);
                continue;
            }
            JsonNode parsed;
            try {
                parsed = JSON.readTree(value);
            } catch (Exception notJson) {
                parsed = null;
            }
            try {
                if (parsed != null && parsed.isArray()) {
                    java.util.List<String> items = new java.util.ArrayList<>();
                    for (JsonNode item : parsed) {
                        if (item.isContainerNode()) { items = null; break; }
                        items.add(item.isTextual() ? item.textValue() : item.asText());
                    }
                    if (items != null) out.put(key, JSON.writeValueAsString(items));
                } else if (parsed == null || parsed.isValueNode()) {
                    String single = parsed != null && parsed.isTextual() ? parsed.textValue() : value.trim();
                    out.put(key, JSON.writeValueAsString(java.util.List.of(single)));
                }
            } catch (Exception impossible) {
                throw new IllegalStateException("cannot serialize normalized action input", impossible);
            }
        }
        return Map.copyOf(out);
    }

    public static void validate(String actionRef, Map<String, String> suppliedInputs) {
        Contract contract = contractFor(actionRef);
        if (!contract.strictInputs()) return;
        Map<String, String> inputs = suppliedInputs == null ? Map.of() : suppliedInputs;

        for (String supplied : inputs.keySet()) {
            if (!contract.inputs().containsKey(supplied)) {
                throw new IllegalArgumentException(
                        "action-input-unexpected:action=" + actionRef + ":key=" + supplied);
            }
        }
        for (Map.Entry<String, InputSpec> entry : contract.inputs().entrySet()) {
            String key = entry.getKey();
            InputSpec spec = entry.getValue();
            boolean present = inputs.containsKey(key);
            if (spec.required() && !present) {
                throw new IllegalArgumentException(
                        "action-input-required:action=" + actionRef + ":key=" + key);
            }
            if (!present) continue;
            String value = inputs.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "action-input-null:action=" + actionRef + ":key=" + key);
            }
            if (!spec.allowEmpty() && value.isBlank()) {
                throw new IllegalArgumentException(
                        "action-input-blank:action=" + actionRef + ":key=" + key);
            }
            validateType(actionRef, key, value, spec);
        }
    }

    private static void validateType(String actionRef, String key, String value, InputSpec spec) {
        switch (spec.type()) {
            case STRING -> { }
            case INTEGER -> {
                final int parsed;
                try {
                    parsed = Integer.parseInt(value.trim());
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException(
                            "action-input-type:action=" + actionRef + ":key=" + key + ":expected=integer",
                            invalid);
                }
                if (spec.minInteger() != null && parsed < spec.minInteger()
                        || spec.maxInteger() != null && parsed > spec.maxInteger()) {
                    throw new IllegalArgumentException(
                            "action-input-range:action=" + actionRef + ":key=" + key
                                    + ":min=" + spec.minInteger() + ":max=" + spec.maxInteger());
                }
            }
            case JSON_STRING_ARRAY -> {
                try {
                    JsonNode parsed = JSON.readTree(value);
                    if (parsed == null || !parsed.isArray()) {
                        throw new IllegalArgumentException(
                                "action-input-type:action=" + actionRef + ":key=" + key
                                        + ":expected=json-string-array");
                    }
                    for (JsonNode item : parsed) {
                        if (!item.isTextual()) {
                            throw new IllegalArgumentException(
                                    "action-input-type:action=" + actionRef + ":key=" + key
                                            + ":expected=json-string-array");
                        }
                    }
                } catch (IllegalArgumentException invalid) {
                    throw invalid;
                } catch (Exception invalidJson) {
                    throw new IllegalArgumentException(
                            "action-input-type:action=" + actionRef + ":key=" + key
                                    + ":expected=json-string-array",
                            invalidJson);
                }
            }
        }
    }

    private static Contract contract(String purpose, Map<String, InputSpec> inputs) {
        return new Contract(true, inputs, purpose);
    }

    private static InputSpec requiredString(String description) {
        return new InputSpec(true, false, InputType.STRING, null, null, description);
    }

    private static InputSpec optionalString(String description) {
        return new InputSpec(false, true, InputType.STRING, null, null, description);
    }

    private static InputSpec requiredJsonArray(String description) {
        return new InputSpec(true, false, InputType.JSON_STRING_ARRAY, null, null, description);
    }

    private static InputSpec optionalJsonArray(String description) {
        return new InputSpec(false, false, InputType.JSON_STRING_ARRAY, null, null, description);
    }

    private static InputSpec optionalInteger(int min, int max, String description) {
        return new InputSpec(false, false, InputType.INTEGER, min, max, description);
    }
}
