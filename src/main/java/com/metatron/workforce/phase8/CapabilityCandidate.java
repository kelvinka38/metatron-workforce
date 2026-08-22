package com.metatron.workforce.phase8;

import java.util.Objects;

public final class CapabilityCandidate {

    private final String id;
    private final String workerId;
    private final String behavior;
    private final String originatingEvidence;
    private final String validationEvidence;

    private CapabilityCandidate(
            String id,
            String workerId,
            String behavior,
            String originatingEvidence,
            String validationEvidence) {

        this.id = require(id);
        this.workerId = require(workerId);
        this.behavior = require(behavior);
        this.originatingEvidence = require(originatingEvidence);
        this.validationEvidence = validationEvidence;
    }

    public static CapabilityCandidate propose(
            String id,
            String workerId,
            String behavior,
            String originatingEvidence) {

        return new CapabilityCandidate(
                id,
                workerId,
                behavior,
                originatingEvidence,
                null);
    }

    public CapabilityCandidate validate(String evidence) {
        return new CapabilityCandidate(
                id,
                workerId,
                behavior,
                originatingEvidence,
                require(evidence));
    }

    public boolean validated() {
        return validationEvidence != null;
    }

    public String id() {
        return id;
    }

    public String workerId() {
        return workerId;
    }

    public String behavior() {
        return behavior;
    }

    public String originatingEvidence() {
        return originatingEvidence;
    }

    public String validationEvidence() {
        return validationEvidence;
    }

    private static String require(String value) {
        Objects.requireNonNull(value);

        if (value.isBlank()) {
            throw new IllegalArgumentException("Value must not be blank.");
        }

        return value;
    }
}
