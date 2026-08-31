package com.metatron.workforce.observation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adapter implemented by an authoritative Observation capability. Execution evidence may be supplied
 * as input/reference material, but the verifier must independently determine the observed state.
 */
public interface ObservationVerifier {
    boolean supports(ObservationRequirement requirement);

    Optional<ObservationReport> observe(ObservationRequirement requirement,
                                        List<String> executionEvidenceReferences,
                                        Instant at);
}
