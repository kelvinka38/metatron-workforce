package com.metatron.workforce.interaction.security;

import java.util.Optional;

public interface CredentialProvider {
    Optional<String> get(String name);
}
