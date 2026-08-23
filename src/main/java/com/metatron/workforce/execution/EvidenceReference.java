package com.metatron.workforce.execution;

import java.time.Instant;

public record EvidenceReference(
        String evidenceId,
        String executionId,
        String type,
        String location,
        Instant createdAt
){}
