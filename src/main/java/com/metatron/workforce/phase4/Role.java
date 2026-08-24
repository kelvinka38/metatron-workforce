package com.metatron.workforce.phase4;

import java.util.Objects;

/** Functional role; role identity is not itself authority or authorization. */
public record Role(String roleId, String name, String responsibilityScope) {
    public Role {
        requireText(roleId, "roleId");
        requireText(name, "name");
        requireText(responsibilityScope, "responsibilityScope");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
