package com.metatron.workforce.phase12;

import com.metatron.workforce.execution.ExecutionRecord;
import com.metatron.workforce.execution.RuntimeBinding;
import com.metatron.workforce.execution.RuntimeExecutionContinuity;
import com.metatron.workforce.phase6.AuthorizationDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimePersistenceRecord;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.RuntimeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G12 hardening acceptance for security-context boundaries and runtime continuity.
 *
 * These tests validate the existing implementation boundary; they do not claim
 * deployment-level production evidence by themselves.
 */
class Phase12SecurityAndRuntimeBoundaryAcceptanceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void authorizationPolicyCannotCrossOrganizationContext() {
        AuthorizationService service = new AuthorizationService();
        AuthorizationRequest orgA = request("REQ-A", "ORG-A", "FARM-A", "EVID-A");
        AuthorizationRequest orgB = request("REQ-B", "ORG-B", "FARM-B", "EVID-B");

        AuthorizationDecision allowed = service.resolve(
                orgA, T0.plusSeconds(1), "HEAD-A",
                r -> r.organizationContextId().equals("ORG-A") && r.scope().equals("FARM-A"));
        AuthorizationDecision denied = service.resolve(
                orgB, T0.plusSeconds(1), "HEAD-A",
                r -> r.organizationContextId().equals("ORG-A") && r.scope().equals("FARM-A"));

        assertEquals(AuthorizationDecision.Outcome.ALLOW, allowed.outcome());
        assertTrue(allowed.usableAt(T0.plusSeconds(1)));
        assertEquals(AuthorizationDecision.Outcome.DENY, denied.outcome());
        assertFalse(denied.usableAt(T0.plusSeconds(1)));
        assertEquals("ORG-B", orgB.organizationContextId());
        assertEquals("FARM-B", orgB.scope());
        assertEquals("EVID-B", denied.evidenceReference());
    }

    @Test
    void runtimeReplacementPreservesExecutionIdentityAndPersistenceEvidence() {
        RuntimeRegistry registry = new RuntimeRegistry();
        RuntimeInstance previous = registry.register(new RuntimeInstance("WORKER-1"));
        previous.transition(RuntimeState.RUNNING);

        ExecutionRecord execution = new ExecutionRecord("EXEC-1", "ASSIGN-1", "WORKER-1");
        RuntimeBinding binding = new RuntimeBinding(execution, previous);

        RuntimeInstance current = registry.register(new RuntimeInstance("WORKER-1"));
        current.transition(RuntimeState.READY);
        RuntimeBinding rebound = new RuntimeBinding(execution, current);
        RuntimeExecutionContinuity continuity = new RuntimeExecutionContinuity(
                execution.executionId(), previous.runtimeId(), current.runtimeId());
        RuntimePersistenceRecord persisted = new RuntimePersistenceRecord(
                current.runtimeId(), current.workerId(), current.state().name(), Instant.now());

        assertEquals(execution.executionId(), binding.executionId());
        assertEquals(execution.executionId(), rebound.executionId());
        assertNotEquals(binding.runtimeId(), rebound.runtimeId());
        assertEquals(execution.executionId(), continuity.executionId());
        assertEquals(previous.runtimeId(), continuity.previousRuntimeId());
        assertEquals(current.runtimeId(), continuity.currentRuntimeId());
        assertEquals(current.runtimeId(), persisted.runtimeId());
        assertEquals("WORKER-1", persisted.workerId());
        assertEquals(RuntimeState.READY.name(), persisted.state());
        assertSame(current, registry.get(current.runtimeId()));
        assertEquals(2, registry.size());
    }

    private AuthorizationRequest request(String requestId, String organization, String scope, String evidence) {
        return new AuthorizationRequest(
                requestId,
                "WORKER-1",
                "HEAD",
                "EXECUTE",
                scope,
                organization,
                "AUTH-" + organization,
                "DEL-" + organization,
                "POLICY-ORG-BOUNDARY",
                T0,
                T0,
                T0.plusSeconds(3600),
                evidence);
    }
}
