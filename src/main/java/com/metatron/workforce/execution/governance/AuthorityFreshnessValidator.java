package com.metatron.workforce.execution.governance;

import java.util.Objects;

/** Hot-path local freshness check against the current indexed authority digest. */
public final class AuthorityFreshnessValidator {
    private final GovernanceStateStore store;

    public AuthorityFreshnessValidator(GovernanceStateStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void requireCurrent(AuthoritySnapshot snapshot) {
        String current = store.currentAuthorityDigest(snapshot.targetEntity())
                .orElseThrow(() -> new GovernanceDeniedException("AUTHORITY_UNRESOLVED",
                        "no current authority digest for target:" + snapshot.targetEntity()));
        if (!current.equals(snapshot.digest())) {
            throw new GovernanceDeniedException("AUTHORITY_STALE",
                    "snapshot=" + snapshot.digest() + ",current=" + current);
        }
    }
}
