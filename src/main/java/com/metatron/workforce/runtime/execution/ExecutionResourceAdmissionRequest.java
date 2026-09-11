package com.metatron.workforce.runtime.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ExecutionResourceAdmissionRequest(
        String requestId,
        String attemptId,
        long attemptFencingToken,
        String objectiveId,
        String schedulingDecisionRef,
        int priority,
        Instant deadline,
        List<ResourceClaim> claims,
        String localityHint,
        Instant submittedAt) {
    public ExecutionResourceAdmissionRequest {
        requestId=require(requestId,"requestId"); attemptId=require(attemptId,"attemptId"); objectiveId=require(objectiveId,"objectiveId"); schedulingDecisionRef=require(schedulingDecisionRef,"schedulingDecisionRef");
        if(attemptFencingToken<1) throw new IllegalArgumentException("attemptFencingToken must be positive");
        Objects.requireNonNull(deadline,"deadline"); Objects.requireNonNull(submittedAt,"submittedAt");
        claims=claims==null?List.of():List.copyOf(claims); localityHint=localityHint==null?"":localityHint.trim();
    }
    private static String require(String v,String f){if(v==null||v.isBlank())throw new IllegalArgumentException(f+" required");return v.trim();}
}
