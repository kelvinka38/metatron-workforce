package com.metatron.workforce.adapter.telegram;

import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredTelegramIdentityResolverTest {
    private final ConfiguredTelegramIdentityResolver resolver = new ConfiguredTelegramIdentityResolver(
            42L,
            new ActorRef("HUMAN-001", ActorRef.ActorType.HUMAN),
            new ActorRef("WORKER-001", ActorRef.ActorType.WORKER),
            "ORG-001");

    @Test
    void configuredUserResolvesToCanonicalIdentity() {
        TelegramIdentityResolver.Resolution result = resolver.resolve(42L, 900L);

        assertEquals("HUMAN-001", result.human().actorId());
        assertEquals("WORKER-001", result.target().actorId());
        assertEquals("ORG-001", result.organizationContextId());
    }

    @Test
    void unknownUserIsDenied() {
        assertThrows(SecurityException.class, () -> resolver.resolve(43L, 900L));
    }
}
