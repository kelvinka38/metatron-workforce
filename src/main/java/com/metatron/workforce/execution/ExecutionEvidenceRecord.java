package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionEvidenceRecord(
        String executionId,
        String evidenceId,
        String type,
        String location,
        Instant createdAt
) {}
