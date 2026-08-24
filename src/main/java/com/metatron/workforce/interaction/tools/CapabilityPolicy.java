package com.metatron.workforce.interaction.tools;

import java.util.Objects;
import java.util.Set;

/** Explicit capability allow-list. Deny by default. */
public final class CapabilityPolicy {
    private final Set<String> allowed;

    public CapabilityPolicy(Set<String> allowed) {
        this.allowed = Set.copyOf(Objects.requireNonNull(allowed, "allowed"));
    }

    public boolean allows(String capability) {
        return capability != null && allowed.contains(capability);
    }
}
