package com.metatron.workforce.runtime;

import com.metatron.workforce.core.WorkforceCoreService;
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
    private static final String OBJECTIVE = "objective:runtime-effect";
    private static final String ASSIGNMENT = "assignment:runtime-effect";
    private static final String DISPATCH = OBJECTIVE + ":graph:1:step:step-effect:attempt:2";

    @Test
    void governedRuntimeCommandConsumesWorkAndCreatesRealAttributedEffect() {
        AtomicInteger effects = new AtomicInteger();
        AtomicReference<AutonomousExecutionCapability.CapabilityRequest> received = new AtomicReference<>();
        AutonomousExecutionCapability capability = capability(effects, received);
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(
                List.of(capability), governedCore());

        RuntimeExecutionResult result = service.execute(command(AUTHORIZATION, WORKER, ASSIGNMENT, DISPATCH));

        assertEquals(1, effects.get());
        AutonomousExecutionCapability.CapabilityRequest request = received.get();
        assertEquals("human:founder", request.humanId());
        assertEquals("organization:metatron", request.organizationContextId());
        assertEquals(OBJECTIVE, request.objectiveId());
        assertEquals(WORKER, request.allocatedWorkerId());
        assertEquals(ASSIGNMENT, request.assignmentReference());
        assertEquals(AUTHORIZATION, request.authorizationReference());
        assertEquals(DISPATCH, request.dispatchReference());
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
    void unavailableHardPrerequisiteIsExcludedFromCatalogAndCannotExecute() {
        AutonomousExecutionCapability unavailable = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return CAPABILITY; }
            @Override public String authorityReference() { return AUTHORITY; }
            @Override public String authorizationReference() { return AUTHORIZATION; }
            @Override public PlanningReadiness planningReadiness() { return PlanningReadiness.NOT_CONFIGURED; }
            @Override public boolean supportsWorker(String workerId) { return WORKER.equals(workerId); }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                throw new AssertionError("not-ready capability must never execute");
            }
        };
        RuntimeCapabilityExecutionService service =
                new RuntimeCapabilityExecutionService(List.of(unavailable), governedCore());

        assertTrue(service.capabilityCatalog().isEmpty());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.execute(command(AUTHORIZATION, WORKER, ASSIGNMENT, DISPATCH)));
        assertTrue(failure.getMessage().contains("runtime-capability-not-ready"));
    }


    @Test
    void transportOnlyEnvelopeCannotCreateEffectWithoutGovernedBindings() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(
                List.of(capability(effects, new AtomicReference<>())), governedCore());
        RuntimeExecutionCommand transportOnly = new RuntimeExecutionCommand(
                "execution", WORKER, "runtime", work());

        assertThrows(SecurityException.class, () -> service.execute(transportOnly));
        assertEquals(0, effects.get());
    }

    @Test
    void fabricatedAssignmentWrongAuthorizationWorkerCapabilityOrDispatchFailsClosedBeforeEffect() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeCapabilityExecutionService service = new RuntimeCapabilityExecutionService(
                List.of(capability(effects, new AtomicReference<>())), governedCore());

        assertThrows(SecurityException.class, () -> service.execute(
                command("authorization:wrong", WORKER, ASSIGNMENT, DISPATCH)));
        assertThrows(SecurityException.class, () -> service.execute(
                command(AUTHORIZATION, "worker:wrong", ASSIGNMENT, DISPATCH)));
        assertThrows(SecurityException.class, () -> service.execute(
                command(AUTHORIZATION, WORKER, "assignment:fabricated", DISPATCH)));
        assertThrows(SecurityException.class, () -> service.execute(
                command(AUTHORIZATION, WORKER, ASSIGNMENT, "dispatch:fabricated")));

        ExecutionWorkSpec unavailable = new ExecutionWorkSpec(
                "step-missing", "missing", "fixture", "capability.missing", List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("effect"), List.of("evidence"));
        RuntimeExecutionCommand missing = new RuntimeExecutionCommand(
                "execution:missing", WORKER, "runtime:missing", unavailable,
                "human:founder", "organization:metatron", "objective:missing",
                ASSIGNMENT, AUTHORIZATION, "objective:missing:graph:1:step:step-missing:attempt:1", 1);
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

    private static WorkforceCoreService governedCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:runtime-effect", WorkforceCoreService.ParticipantType.AI,
                "test:runtime-effect");
        core.admitWorker(WORKER, "participant:runtime-effect");
        core.participate("participation:runtime-effect", WORKER, "organization:metatron",
                "position:executor", "role:executor");
        core.assign(ASSIGNMENT, OBJECTIVE, WORKER, "participation:runtime-effect",
                AUTHORITY, AUTHORIZATION, "governed runtime actual effect");
        return core;
    }

    private static RuntimeExecutionCommand command(
            String authorization, String worker, String assignment, String dispatch) {
        return new RuntimeExecutionCommand(
                "execution:runtime-effect", worker, "runtime:effect-01", work(),
                "human:founder", "organization:metatron", OBJECTIVE,
                assignment, authorization, dispatch, 2);
    }

    private static ExecutionWorkSpec work() {
        return new ExecutionWorkSpec(
                "step-effect", "create one actual effect", "fixture", CAPABILITY, List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("effect observed"), List.of("effect evidence"));
    }
}
