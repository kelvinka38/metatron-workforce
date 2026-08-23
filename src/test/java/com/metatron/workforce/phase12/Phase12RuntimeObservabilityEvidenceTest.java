package com.metatron.workforce.phase12;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Phase12RuntimeObservabilityEvidenceTest {


@Test
void runtimeMetricMustBeCaptured(){

    RuntimeObservabilityEvidenceService service =
        new RuntimeObservabilityEvidenceService();


    RuntimeObservabilityEvidence evidence =
        service.capture(
            "exec-001",
            "worker-001",
            200,
            "SUCCESS",
            0
        );


    assertEquals("SUCCESS", evidence.getStatus());
    assertEquals(200,evidence.getDurationMs());

}

}
