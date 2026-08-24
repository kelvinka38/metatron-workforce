package com.metatron.workforce.interaction.security;

import java.util.Optional;

public final class EnvironmentCredentialProvider implements CredentialProvider {
    @Override
    public Optional<String> get(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }
}
