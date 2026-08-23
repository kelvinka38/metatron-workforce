package com.metatron.workforce.phase12;


public class RuntimeObservabilityEvidenceService {


    public RuntimeObservabilityEvidence capture(
            String executionId,
            String workerId,
            long durationMs,
            String status,
            int retries
    ){

        return new RuntimeObservabilityEvidence(
                executionId,
                workerId,
                durationMs,
                status,
                retries
        );
    }
}
