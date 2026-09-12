package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttemptService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HighwayResourceBridgeServiceTest {
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void highwayWriteClaimsConvergeOnCanonicalLeaseAndFenceStaleOwner() {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionResourceManager resources = new ExecutionResourceManager(attempts, new InMemoryResourceStateStore(), Map.of());
        HighwayResourceBridgeService bridge = new HighwayResourceBridgeService(attempts, resources);

        HighwayResourceBridgeService.Grant a = bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-a", "workforce-deploy", SHA, "release-a", "executor-a", 1,
                Map.of("prod:workforce", "WRITE")), t);
        assertEquals(1, a.leases().size());
        assertEquals("prod:workforce", a.leases().getFirst().resourceId());

        assertThrows(IllegalStateException.class, () -> bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-b", "workforce-deploy", SHA, "release-b", "executor-b", 1,
                Map.of("prod:workforce", "WRITE")), t.plusSeconds(1)));

        var aToken = new HighwayResourceBridgeService.LeaseToken(
                a.leases().getFirst().leaseId(), a.leases().getFirst().resourceId(),
                a.leases().getFirst().resourceFencingToken());
        assertDoesNotThrow(() -> bridge.validate(new HighwayResourceBridgeService.LeaseRequest(
                a.taskId(), a.attemptId(), a.attemptFencingToken(), List.of(aToken)), t.plusSeconds(2)));

        resources.reconcileExpired(t.plusSeconds(61));
        HighwayResourceBridgeService.Grant b = bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-b", "workforce-deploy", SHA, "release-b", "executor-b", 1,
                Map.of("prod:workforce", "WRITE")), t.plusSeconds(61));
        assertTrue(b.leases().getFirst().resourceFencingToken() > a.leases().getFirst().resourceFencingToken());

        assertThrows(SecurityException.class, () -> bridge.validate(new HighwayResourceBridgeService.LeaseRequest(
                a.taskId(), a.attemptId(), a.attemptFencingToken(), List.of(aToken)), t.plusSeconds(62)));

        var bToken = new HighwayResourceBridgeService.LeaseToken(
                b.leases().getFirst().leaseId(), b.leases().getFirst().resourceId(),
                b.leases().getFirst().resourceFencingToken());
        assertDoesNotThrow(() -> bridge.validate(new HighwayResourceBridgeService.LeaseRequest(
                b.taskId(), b.attemptId(), b.attemptFencingToken(), List.of(bToken)), t.plusSeconds(62)));
    }

    @Test
    void readClaimsCanShareButWriteCannotDowngradeIntoReadAuthority() {
        Instant t = Instant.parse("2026-09-12T00:00:00Z");
        ExecutionAttemptService attempts = new ExecutionAttemptService();
        ExecutionResourceManager resources = new ExecutionResourceManager(attempts, new InMemoryResourceStateStore(), Map.of());
        HighwayResourceBridgeService bridge = new HighwayResourceBridgeService(attempts, resources);

        var one = bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-r1", "verify", SHA, "corr-r1", "executor-r1", 1,
                Map.of("prod:workforce", "READ")), t);
        var two = bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-r2", "verify", SHA, "corr-r2", "executor-r2", 1,
                Map.of("prod:workforce", "READ")), t.plusSeconds(1));
        assertEquals(ResourceClaim.Mode.READ_SHARED.name(), one.leases().getFirst().mode());
        assertEquals(ResourceClaim.Mode.READ_SHARED.name(), two.leases().getFirst().mode());

        assertThrows(IllegalStateException.class, () -> bridge.acquire(new HighwayResourceBridgeService.AcquireRequest(
                "hw-w", "workforce-deploy", SHA, "corr-w", "executor-w", 1,
                Map.of("prod:workforce", "WRITE")), t.plusSeconds(2)));
    }
}
