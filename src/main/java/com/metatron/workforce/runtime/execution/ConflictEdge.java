package com.metatron.workforce.runtime.execution;

import java.util.Objects;

/** Deterministic evidence that two integration candidates overlap on a protected semantic surface. */
public record ConflictEdge(String leftEntryId,
                           String rightEntryId,
                           Kind kind,
                           String subject,
                           String evidence) {
    public enum Kind { PATH, RESOURCE }

    public ConflictEdge {
        leftEntryId = require(leftEntryId, "leftEntryId");
        rightEntryId = require(rightEntryId, "rightEntryId");
        if (leftEntryId.equals(rightEntryId)) throw new IllegalArgumentException("conflict edge requires distinct entries");
        Objects.requireNonNull(kind, "kind");
        subject = require(subject, "subject");
        evidence = require(evidence, "evidence");
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
