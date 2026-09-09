package com.metatron.workforce.operating;

import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.management.GatewayDirectorStaffingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerConstitutionServiceTest {
    @TempDir Path temp;

    @Test
    void gatewayDirectorConstitutionIsDurableCompleteAndEvidenceBound() {
        Path state = temp.resolve("worker-constitution.json");
        WorkerConstitutionService service =
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state));
        GatewayDirectorStaffingPolicy policy = new GatewayDirectorStaffingPolicy();
        Instant at = Instant.parse("2026-09-09T16:30:00Z");

        var binding = service.ensureConstitution(policy, at);
        var context = service.contextFor(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                policy.formationSpec().participationId());

        assertEquals("position-contract:position:gateway-director:v1", binding.contractId());
        assertEquals(GatewayDirectorAppointmentCapability.ROLE_REF, context.contract().roleRef());
        assertTrue(context.contract().mission().contains("controlled institutional boundary"));
        assertTrue(context.contract().responsibilities().size() >= 6);
        assertTrue(context.contract().reportingLines().stream()
                .anyMatch(line -> line.targetRef().equals(GatewayDirectorAppointmentCapability.FOUNDER_HUMAN_ID)));
        assertTrue(context.contract().resourceScopes().stream()
                .anyMatch(resource -> resource.resourceRef().equals("cost")));
        assertTrue(context.contract().escalationRoutes().stream()
                .anyMatch(route -> route.category().contains("SECURITY")));
        assertTrue(context.contract().successMeasures().stream()
                .anyMatch(measure -> measure.measureRef().equals("gateway-user-smoothness")));
        assertTrue(context.contract().decisionRights().stream()
                .anyMatch(right -> right.contains("staffing")));
        assertEquals("CONTINUOUS_ACCOUNTABILITY_WITH_DEMAND_DRIVEN_BOUNDED_EXECUTION",
                context.contract().operatingCoverage());
        assertEquals("UTC", context.contract().workingTimeZone());
        assertEquals(1, context.contract().maxConcurrentAssignments());
        assertTrue(context.evidenceReferences().stream()
                .anyMatch(ref -> ref.contains("authority-envelope:")));

        // Re-open from disk and prove the Position contract is not an in-memory prompt projection.
        WorkerConstitutionService reloaded =
                new WorkerConstitutionService(new FileWorkerConstitutionStateStore(state));
        var durable = reloaded.contextFor(
                GatewayDirectorAppointmentCapability.WORKER_ID,
                policy.formationSpec().participationId());
        assertEquals(context.contract().mission(), durable.contract().mission());
        assertEquals(context.contract().successMeasures(), durable.contract().successMeasures());
    }

    @Test
    void performanceExperienceAndRecoveryLearningAreFirstClassDurableReality() {
        WorkerConstitutionService service = WorkerConstitutionService.inMemory();
        GatewayDirectorStaffingPolicy policy = new GatewayDirectorStaffingPolicy();
        service.ensureConstitution(policy, Instant.parse("2026-09-09T10:00:00Z"));

        String workerId = GatewayDirectorAppointmentCapability.WORKER_ID;
        service.observeAction(
                workerId,
                "gateway.audit.read",
                false,
                "Gateway audit attempt failed on unavailable dependency",
                Instant.parse("2026-09-09T10:05:00Z"),
                List.of("action-fabric:failed:1"));
        service.observeAction(
                workerId,
                "gateway.audit.read",
                true,
                "Gateway audit succeeded after dependency recovery",
                Instant.parse("2026-09-09T10:06:00Z"),
                List.of("action-fabric:success:2"));

        var evaluation = service.recordPerformance(
                workerId,
                Instant.parse("2026-09-09T10:00:00Z"),
                Instant.parse("2026-09-09T11:00:00Z"),
                2, 1, 2, 2, 1, 1,
                "workforce:operational-performance-evaluator:v1",
                List.of("objective:one", "objective:two", "action-fabric:success:2"));

        assertEquals(0.5, evaluation.objectiveCompletionRatio(), 0.0001);
        assertEquals(0.5, evaluation.actionSuccessRatio(), 0.0001);
        assertEquals(2, service.experiences(workerId).size());
        assertEquals(1, service.learning(workerId).size());
        assertTrue(service.learning(workerId).getFirst().lesson().contains("failed attempt was followed by a successful"));
        assertFalse(service.learning(workerId).getFirst().evidenceReferences().isEmpty());

        var context = service.contextFor(workerId, policy.formationSpec().participationId());
        assertNotNull(context.performance());
        assertEquals(2, context.recentExperience().size());
        assertEquals(1, context.recentLearning().size());
        assertTrue(context.renderedContext().contains("CURRENT CONTEXTUAL PERFORMANCE"));
        assertTrue(context.renderedContext().contains("EVIDENCE-DERIVED LEARNING"));
    }
}
