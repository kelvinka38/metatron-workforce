package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitionBoundAutonomousExecutionCapabilityTest {
    @Test
    void assignedWorkerReasonsBeforeCapabilityAndCognitionEvidenceTravelsWithResult() {
        AtomicReference<WorkerIntelligenceService.Request> observed = new AtomicReference<>();
        WorkerIntelligenceService intelligence = request -> {
            observed.set(request);
            return new WorkerIntelligenceService.Response(
                    "worker-cognition-test",
                    "Use the bounded Commander uptime operation.",
                    List.of(
                            "metatron-cognition-endpoint:metatron-cognition-node-ccx33",
                            "worker-intelligence-provider:OPENAI:model=qwen2.5:7b-instruct-q4_K_M:request=node-request"));
        };
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "host.commander.execute"; }
            @Override public String authorityReference() { return "authority:test"; }
            @Override public String authorizationReference() { return "authorization:test"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                return new CapabilityResult(true, request.allocatedWorkerId(), request.assignmentReference(),
                        "commander:test", List.of("host-commander:uptime-verified"), "uptime ok");
            }
        };
        CognitionBoundAutonomousExecutionCapability capability =
                new CognitionBoundAutonomousExecutionCapability(delegate, intelligence);
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "uptime", "Check Metatron production uptime", "production host",
                "host.commander.execute", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("return verified uptime"), List.of("Commander verified result"));
        AutonomousExecutionCapability.CapabilityRequest request =
                new AutonomousExecutionCapability.CapabilityRequest(
                        "human-primary", "organization:metatron", "objective:uptime", work,
                        "WORKER-GENERAL-ENGINEERING", "assignment:uptime", "authorization:test",
                        "dispatch:uptime", 1)
                        .withExecutionAttempt("attempt:uptime", 1);

        AutonomousExecutionCapability.CapabilityResult result = capability.execute(request);

        assertEquals("WORKER-GENERAL-ENGINEERING", observed.get().workerId());
        assertEquals("objective:uptime", observed.get().objectiveId());
        assertEquals("attempt:uptime", observed.get().executionAttemptId());
        assertTrue(result.evidenceReferences().contains(
                "metatron-cognition-endpoint:metatron-cognition-node-ccx33"));
        assertTrue(result.evidenceReferences().contains("worker-cognitive-decision:worker-cognition-test"));
        assertTrue(result.evidenceReferences().contains("host-commander:uptime-verified"));
    }
}
