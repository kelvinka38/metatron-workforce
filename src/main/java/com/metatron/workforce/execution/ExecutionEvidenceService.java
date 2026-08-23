package com.metatron.workforce.execution;

import java.time.Instant;

public final class ExecutionEvidenceService {

    public EvidenceReference attach(
            String executionId,
            String evidenceId,
            String type,
            String location
    ){

        return new EvidenceReference(
                evidenceId,
                executionId,
                type,
                location,
                Instant.now()
        );
    }
}
