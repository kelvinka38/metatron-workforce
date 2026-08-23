package com.metatron.workforce.phase12;

import java.time.Instant;

public class RuntimeObservabilityEvidence {

    private final String executionId;
    private final String workerId;
    private final long durationMs;
    private final String status;
    private final int retryCount;
    private final Instant timestamp;


    public RuntimeObservabilityEvidence(
            String executionId,
            String workerId,
            long durationMs,
            String status,
            int retryCount
    ){
        this.executionId = executionId;
        this.workerId = workerId;
        this.durationMs = durationMs;
        this.status = status;
        this.retryCount = retryCount;
        this.timestamp = Instant.now();
    }


    public String getStatus(){
        return status;
    }

    public long getDurationMs(){
        return durationMs;
    }

    public int getRetryCount(){
        return retryCount;
    }
}
