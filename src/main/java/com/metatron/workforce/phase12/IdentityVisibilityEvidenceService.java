package com.metatron.workforce.phase12;

public class IdentityVisibilityEvidenceService {


    public IdentityVisibilityEvidence capture(
            String executionId,
            String actorId,
            String organizationId,
            String requestedScope,
            String actorOrganization
    ){

        boolean allowed =
                organizationId.equals(actorOrganization);


        return new IdentityVisibilityEvidence(
                executionId,
                actorId,
                organizationId,
                requestedScope,
                allowed ? "ALLOW" : "DENY"
        );
    }
}
