package com.metatron.workforce.operating;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Which capabilities have a registered Position work route. A Position that declares address aliases must name a
 * routed primary capability, or its constitution is refused (and a Head reconciled at start-up is DEGRADED).
 */
@FunctionalInterface
public interface PositionRouteCatalog {
    /** No routes are registered: no Position may declare address aliases. */
    PositionRouteCatalog NONE = capability -> false;

    boolean routes(String capability);

    static PositionRouteCatalog of(Collection<String> capabilities) {
        Set<String> routed = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        return routed::contains;
    }
}
