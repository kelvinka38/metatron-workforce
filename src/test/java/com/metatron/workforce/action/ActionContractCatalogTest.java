package com.metatron.workforce.action;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActionContractCatalogTest {

    @Test
    void rejectsMalformedJsonArrayAndUnexpectedInputsBeforeToolInvocation() {
        AtomicInteger invoked = new AtomicInteger();
        ActionFabric.Action action = new ActionFabric.Action() {
            @Override public String actionRef() { return "workspace.git.run"; }
            @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
            @Override public java.util.Set<String> allowedWorkers() { return java.util.Set.of("worker"); }
            @Override public java.util.Set<String> acceptedAuthorizations() { return java.util.Set.of("auth"); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                invoked.incrementAndGet();
                return ActionFabric.ActionObservation.success(actionRef(), "ok", Map.of(), java.util.List.of());
            }
        };
        ActionFabric fabric = new ActionFabric(java.util.List.of(action));

        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class, () -> fabric.execute(
                request(Map.of("argsJson", "status"))));
        assertTrue(malformed.getMessage().contains("expected=json-string-array"));
        assertEquals(0, invoked.get());

        IllegalArgumentException extra = assertThrows(IllegalArgumentException.class, () -> fabric.execute(
                request(Map.of("argsJson", "[\"status\"]", "surprise", "value"))));
        assertTrue(extra.getMessage().contains("action-input-unexpected"));
        assertEquals(0, invoked.get());

        ActionFabric.ActionObservation valid = fabric.execute(request(Map.of("argsJson", "[\"status\"]")));
        assertTrue(valid.success());
        assertEquals(1, invoked.get());
        assertTrue(valid.evidenceReferences().contains(
                "action-contract:action=workspace.git.run:validated=true"));
    }

    @Test
    void validatesRequiredFieldsTypesAndRangesCentrally() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ActionContractCatalog.validate("workspace.file.read", Map.of()));
        assertTrue(missing.getMessage().contains("action-input-required"));

        IllegalArgumentException range = assertThrows(IllegalArgumentException.class,
                () -> ActionContractCatalog.validate(
                        "workspace.file.search", Map.of("query", "needle", "maxMatches", "501")));
        assertTrue(range.getMessage().contains("action-input-range"));

        IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                () -> ActionContractCatalog.validate(
                        "workspace.test.run", Map.of("tasksJson", "{\"not\":\"an array\"}")));
        assertTrue(type.getMessage().contains("expected=json-string-array"));

        assertDoesNotThrow(() -> ActionContractCatalog.validate(
                "workspace.test.run", Map.of("tasksJson", "[\"test\"]", "workingDirectory", "")));
    }

    @Test
    void promptAndRuntimeUseTheSameCanonicalContract() {
        Map<String, Object> prompt = ActionContractCatalog.promptContract("workspace.process.run");
        assertFalse(prompt.isEmpty());
        assertEquals("run one allowlisted process in the isolated Objective sandbox", prompt.get("purpose"));
        Object rawInputs = prompt.get("inputs");
        assertInstanceOf(Map.class, rawInputs);
        @SuppressWarnings("unchecked")
        Map<String, String> inputs = (Map<String, String>) rawInputs;
        assertEquals(java.util.Set.of("executable", "argsJson", "workingDirectory"), inputs.keySet());

        assertDoesNotThrow(() -> ActionContractCatalog.validate(
                "workspace.process.run",
                Map.of("executable", "python3", "argsJson", "[]", "workingDirectory", "")));
    }

    @Test
    void unknownExtensionActionsRemainBackwardCompatibleAndPermissive() {
        assertTrue(ActionContractCatalog.promptContract("custom.organization.action").isEmpty());
        assertDoesNotThrow(() -> ActionContractCatalog.validate(
                "custom.organization.action", Map.of("arbitrary", "value")));
    }

    private static ActionFabric.ActionRequest request(Map<String, String> inputs) {
        return new ActionFabric.ActionRequest(
                "workspace.git.run",
                "worker",
                "assignment",
                "auth",
                "objective",
                "step",
                "idempotency",
                false,
                inputs);
    }
}
