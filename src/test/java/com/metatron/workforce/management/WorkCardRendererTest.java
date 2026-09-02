package com.metatron.workforce.management;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkCardRendererTest {
    @Test
    void resolvesActualPerformerFromDurableAutonomousStepEvidence() {
        var performers = WorkCardRenderer.performersByStep(List.of(
                "unrelated:evidence",
                "autonomous-step:step-1:capability=repo.audit:work=work-1:worker=worker-a:dispatch=d-1:idempotency=i-1",
                "autonomous-step:step-2:capability=repo.fix:work=work-2:worker=worker-b:dispatch=d-2:idempotency=i-2"));

        assertEquals("worker-a", performers.get("step-1"));
        assertEquals("worker-b", performers.get("step-2"));
    }

    @Test
    void neverInventsPerformerWithoutExecutionAttribution() {
        var performers = WorkCardRenderer.performersByStep(List.of(
                "staffing:reused-worker=worker-a",
                "assignment:some-ref"));

        assertFalse(performers.containsKey("step-1"));
    }
}