package com.metatron.workforce.execution;

public record ExecutionPolicy(
        boolean evidenceRequired,
        boolean auditRequired
) {}
