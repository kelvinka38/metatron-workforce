package com.metatron.workforce.runtime;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.management.AutonomousExecutionCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCapabilityExecutionServiceTest {
    private static final String CAPABILITY = "test.runtime.effect";
    private static final String WORKER = "worker:runtime-effect";
    private static final String AUTHORIZATION = "authorization:runtime-effect:v1";
    private static final String AUTHORITY = "authority:runtime-effect:v1";

    @Test
    void governedRuntimeCommandConsumesWorkAndCreatesRealAttributedEffect() {
        AtomicInteger effects = new AtomicInteger();
        AtomicReference<AutonomousExecutionCapability.CapabilityRequest> received = new AtomicReference<>();
        AutonomousExecutionCapability capability = capability(effects, received);
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(List.of(capability));

        RuntimeExecutionResult result = service.execute(command(AUTHORIZATION, WORKER));

        assertEquals(1, effects.get());
        AutonomousExecutionCapability.CapabilityRequest request = received.get();
        assertEquals("human:founder", request.humanId());
        assertEquals("organization:metatron", request.organizationContextId());
        assertEquals("objective:runtime-effect", request.objectiveId());
        assertEquals(WORKER, request.allocatedWorkerId());
        assertEquals("assignment:runtime-effect", request.assignmentReference());
        assertEquals(AUTHORIZATION, request.authorizationReference());
        assertEquals("dispatch:runtime-effect", request.dispatchReference());
        assertEquals(2, request.dispatchAttempt());
        assertTrue(request.dispatchBound());
        assertEquals("runtime:effect-01", result.runtimeId());
        assertEquals(AUTHORITY, result.authorityReference());
        assertEquals(CAPABILITY, result.capabilityRef());
        assertTrue(result.success());
        assertTrue(result.evidenceReferences().stream().anyMatch(ref ->
                ref.startsWith("runtime-actual-effect:execution=execution:runtime-effect")));
    }

    @Test
    void transportOnlyEnvelopeCannotCreateEffectWithoutGovernedBindings() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(
                List.of(capability(effects, new AtomicReference<>())));
        RuntimeExecutionCommand transportOnly = new RuntimeExecutionCommand(
                "execution", WORKER, "runtime", work());

        assertThrows(SecurityException.class, () -> service.execute(transportOnly));
        assertEquals(0, effects.get());
    }

    @Test
    void wrongAuthorizationWorkerOrCapabilityFailsClosedBeforeEffect() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(
                List.of(capability(effects, new AtomicReference<>())));

        assertThrows(SecurityException.class, () -> service.execute(command("authorization:wrong", WORKER)));
        assertThrows(SecurityException.class, () -> service.execute(command(AUTHORIZATION, "worker:wrong")));

        ExecutionWorkSpec unavailable = new ExecutionWorkSpec(
                "step-missing", "missing", "fixture", "capability.missing", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("effect"), List.of("evidence"));
        RuntimeExecutionCommand missing = new RuntimeExecutionCommand(
                "execution:missing", WORKER, "runtime:missing", unavailable,
                "human:founder", "organization:metatron", "objective:missing",
                "assignment:runtime-effect", AUTHORIZATION, "dispatch:missing", 1);
        assertThrows(IllegalArgumentException.class, () -> service.execute(missing));
        assertEquals(0, effects.get());
    }

    private static AutonomousExecutionCapability capability(
            AtomicInteger effects,
            AtomicReference<AutonomousExecutionCapability.CapabilityRequest> received) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return AUTHORITY; }
            @Override public String authorizationReference() { return AUTHORIZATION; }
            @Override public boolean supportsWorker(String workerId) { return WORKER.equals(workerId); }

            @Override
            public CapabilityResult execute(CapabilityRequest request) {
                received.set(request);
                effects.incrementAndGet();
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "work:runtime-effect", List.of("effect-observed:true"), "real effect created");
            }
        };
    }

    private static RuntimeExecutionCommand command(String authorization, String worker) {
        return new RuntimeExecutionCommand(
                "execution:runtime-effect", worker, "runtime:effect-01", work(),
                "human:founder", "organization:metatron", "objective:runtime-effect",
                "assignment:runtime-effect", authorization, "dispatch:runtime-effect", 2);
    }

    private static ExecutionWorkSpec work() {
        return new ExecutionWorkSpec(
                "step-effect", "create one actual effect", "fixture", CAPABILITY, List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("effect observed"), List.of("effect evidence"));
    }
}
