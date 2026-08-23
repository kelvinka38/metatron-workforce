package com.metatron.workforce.phase12;

import java.time.Instant;

public class IdentityVisibilityEvidence {

    private final String executionId;
    private final String actorId;
    private final String organizationId;
    private final String visibilityScope;
    private final String decision;
    private final Instant timestamp;

    public IdentityVisibilityEvidence(
            String executionId,
            String actorId,
            String organizationId,
            String visibilityScope,
            String decision
    ){
        this.executionId = executionId;
        this.actorId = actorId;
        this.organizationId = organizationId;
        this.visibilityScope = visibilityScope;
        this.decision = decision;
        this.timestamp = Instant.now();
    }

    public String getDecision(){
        return decision;
    }

    public String getVisibilityScope(){
        return visibilityScope;
    }

    public String getActorId(){
        return actorId;
    }

    public Instant getTimestamp(){
        return timestamp;
    }
}
