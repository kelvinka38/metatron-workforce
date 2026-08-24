package com.metatron.workforce.adapter.telegram;

import com.metatron.workforce.phase3.ActorRef;

import java.util.Objects;

/** Single-owner bootstrap resolver. Unknown Telegram users are denied, never provisioned. */
public final class ConfiguredTelegramIdentityResolver implements TelegramIdentityResolver {
    private final long allowedTelegramUserId;
    private final ActorRef human;
    private final ActorRef target;
    private final String organizationContextId;

    public ConfiguredTelegramIdentityResolver(
            long allowedTelegramUserId,
            ActorRef human,
            ActorRef target,
            String organizationContextId) {
        if (allowedTelegramUserId <= 0) throw new IllegalArgumentException("allowedTelegramUserId must be positive");
        this.allowedTelegramUserId = allowedTelegramUserId;
        this.human = Objects.requireNonNull(human, "human");
        this.target = Objects.requireNonNull(target, "target");
        this.organizationContextId = Objects.requireNonNull(organizationContextId, "organizationContextId");
        if (organizationContextId.isBlank()) throw new IllegalArgumentException("organizationContextId must not be blank");
    }

    @Override
    public Resolution resolve(long telegramUserId, long telegramChatId) {
        if (telegramUserId != allowedTelegramUserId) {
            throw new SecurityException("Telegram user is not authorized for this Metatron workspace");
        }
        return new Resolution(human, target, organizationContextId);
    }
}
