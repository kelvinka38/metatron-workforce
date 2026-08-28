package com.metatron.workforce.management;

import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.WorkQueueItem;
import com.metatron.workforce.phase3.WorkQueueService;
import com.metatron.workforce.phase6.ApprovalDecision;
import com.metatron.workforce.phase6.AuthorizationRequest;
import com.metatron.workforce.phase6.AuthorizationService;
import com.metatron.workforce.phase6.ExecutionRecord;
import com.metatron.workforce.phase6.ExecutionService;
import com.metatron.workforce.phase6.Phase6AuthorizationPolicy;
import com.metatron.workforce.phase6.WorkProposal;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeState;
import com.metatron.workforce.runtime.execution.RuntimeExecutionCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GatewayDirectorNorthStarAcceptanceTest {
    @TempDir Path tempDir;

    @Test
    void objectiveAndHistorySurviveServiceReplacement() {
        Path state = tempDir.resolve("management-state.json");
        Instant t0 = Instant.parse("2026-08-28T06:30:00Z");
        ManagementAutonomyService first = new ManagementAutonomyService(new FileManagementStateStore(state));
        first.acceptObjective("OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ORG-METATRON",
                "Deliver Gateway V2 according to the approved specification.", t0);
        first.addAssignmentReference("OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ASSIGN-AUDIT", t0.plusSeconds(1));

        ManagementAutonomyService restored = new ManagementAutonomyService(new FileManagementStateStore(state));
        assertEquals("WORKER-GW-DIRECTOR", restored.get("OBJ-GW-V2").ownerWorkerId());
        assertEquals(List.of("ASSIGN-AUDIT"), restored.get("OBJ-GW-V2").assignmentRefs());
        assertEquals(2, restored.history("OBJ-GW-V2").size());
    }

    @Test
    void gatewayDirectorCanRunBoundedManagementLoopWithoutHumanOrchestration() {
        Instant t0 = Instant.parse("2026-08-28T06:30:00Z");
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService(
                new FileManagementStateStore(tempDir.resolve("north-star.json")));
        WorkQueueService queue = new WorkQueueService(
                (actor, target, org) -> AuthorizationContext.allowed("WP-AUTH-" + org), clock);
        AuthorizationService authorization = new AuthorizationService(
                request -> Phase6AuthorizationPolicy.Decision.allowed("AUTH-GW-V2"));
        ExecutionService execution = new ExecutionService();
        AutonomousManagementCoordinator coordinator = new AutonomousManagementCoordinator(
                management, queue, authorization, execution, new RuntimeExecutionCoordinator());

        ActorRef founder = new ActorRef("FOUNDER", ActorRef.ActorType.HUMAN);
        ActorRef staffingAuthority = new ActorRef("ORG-STAFFING-AUTHORITY", ActorRef.ActorType.ORGANIZATIONAL_GROUP);
        var intake = coordinator.acceptObjectiveFromHuman(
                "OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ORG-METATRON",
                "Deliver Gateway V2 according to the approved specification.", founder, t0);
        assertEquals(WorkQueueItem.QueueState.ACKNOWLEDGED, intake.queueItem().state());

        var staffing = coordinator.assessAndRaiseStaffingNeed(
                "OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ORG-METATRON",
                "gateway.security.audit", 2.0, 1.0, staffingAuthority, t0.plusSeconds(1));
        assertTrue(staffing.isPresent());
        assertEquals(1.0, staffing.orElseThrow().need().capacityGap());
        assertEquals(WorkQueueItem.QueueState.DELIVERED, staffing.orElseThrow().queueItem().state());

        WorkProposal proposal = new WorkProposal("PROP-GW-AUDIT", "WORKER-GW-DIRECTOR", "WORK-GW-AUDIT",
                "gateway.audit.read", "gateway:v2", "ORG-METATRON", t0.plusSeconds(2));
        ApprovalDecision approval = new ApprovalDecision("DEC-GW-AUDIT", "PROP-GW-AUDIT", "AUTHORITY-GATEWAY",
                true, t0.plusSeconds(3), "DELEGATION-GW-V2", "within delegated Gateway V2 scope");
        AuthorizationRequest request = new AuthorizationRequest("WORKER-GW-DIRECTOR", "ROLE-GATEWAY-DIRECTOR",
                "AUTHORITY-GATEWAY", "gateway:v2", "gateway.audit.read", "ORG-METATRON",
                t0.plusSeconds(4), "WORK-GW-AUDIT");

        RuntimeInstance runtime = new RuntimeInstance("RUNTIME-GW-DIRECTOR", "WORKER-GW-DIRECTOR", RuntimeState.READY);
        var managedExecution = coordinator.authorizeBindAndExecute(
                "OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ASSIGN-GW-AUDIT", "WORKPKG-GW-AUDIT",
                proposal, approval, request, runtime,
                p -> ExecutionService.ExecutionResult.success(List.of("gateway-v1-state"), List.of("EVIDENCE-GW-AUDIT")),
                "EXEC-GW-AUDIT", t0.plusSeconds(5), t0.plusSeconds(6));

        assertEquals("AUTH-GW-V2", managedExecution.handoff().authorizationId());
        assertEquals("WORKER-GW-DIRECTOR", managedExecution.runtimeContext().workerId());
        assertEquals(ExecutionRecord.Status.SUCCEEDED, managedExecution.executionRecord().status());

        management.markBlocked("OBJ-GW-V2", "WORKER-GW-DIRECTOR", "one worker unavailable", t0.plusSeconds(7));
        management.recoverLocally("OBJ-GW-V2", "WORKER-GW-DIRECTOR",
                "reassign audit subtask to available worker", t0.plusSeconds(8));
        assertEquals(ManagementObjective.Status.ACTIVE, management.get("OBJ-GW-V2").status());

        var delivery = coordinator.deliverWithEvidence("OBJ-GW-V2", "WORKER-GW-DIRECTOR", "ORG-METATRON",
                List.of("EVIDENCE-GW-AUDIT", "EXEC-GW-AUDIT"), founder, t0.plusSeconds(9));
        assertEquals(ManagementObjective.Status.DELIVERED, delivery.objective().status());
        assertEquals(WorkQueueItem.QueueState.DELIVERED, delivery.reportQueueItem().state());

        ManagementAutonomyService afterRuntimeReplacement = new ManagementAutonomyService(
                new FileManagementStateStore(tempDir.resolve("north-star.json")));
        assertEquals(ManagementObjective.Status.DELIVERED, afterRuntimeReplacement.get("OBJ-GW-V2").status());
        assertTrue(afterRuntimeReplacement.history("OBJ-GW-V2").stream()
                .anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.LOCAL_RECOVERY));
    }

    @Test
    void deniedAuthorizationCannotCreateExecutionHandoff() {
        Instant t0 = Instant.parse("2026-08-28T06:30:00Z");
        ManagementAutonomyService management = new ManagementAutonomyService();
        management.acceptObjective("OBJ-DENY", "DIRECTOR", "ORG", "denied path", t0);
        WorkQueueService queue = new WorkQueueService((a, b, o) -> AuthorizationContext.allowed("WP"), Clock.systemUTC());
        AuthorizationService authorization = new AuthorizationService(
                request -> Phase6AuthorizationPolicy.Decision.denied("AUTH-DENY", "scope denied"));
        AutonomousManagementCoordinator coordinator = new AutonomousManagementCoordinator(
                management, queue, authorization, new ExecutionService(), new RuntimeExecutionCoordinator());
        WorkProposal proposal = new WorkProposal("P", "DIRECTOR", "W", "act", "scope", "ORG", t0);
        ApprovalDecision approval = new ApprovalDecision("D", "P", "A", true, t0, "AR", "approved");
        AuthorizationRequest request = new AuthorizationRequest("DIRECTOR", "ROLE", "AUTHORITY", "scope", "act", "ORG", t0, "W");
        RuntimeInstance runtime = new RuntimeInstance("R", "DIRECTOR", RuntimeState.READY);

        assertThrows(SecurityException.class, () -> coordinator.authorizeBindAndExecute(
                "OBJ-DENY", "DIRECTOR", "ASSIGN", "PKG", proposal, approval, request, runtime,
                p -> ExecutionService.ExecutionResult.success(List.of(), List.of()), "EXEC", t0, t0));
    }
}
