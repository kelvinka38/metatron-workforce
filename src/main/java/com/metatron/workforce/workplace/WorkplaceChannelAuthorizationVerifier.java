package com.metatron.workforce.workplace;

/** External authorization verifier; Workforce stores only validated references and never mints channel authority. */
public interface WorkplaceChannelAuthorizationVerifier {
    enum Purpose { QUERY, DELIVERY }

    boolean verifies(String objectiveId, String humanId, String channel,
                     String authorizationReference, Purpose purpose);
}
