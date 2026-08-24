package com.metatron.workforce.adapter.telegram;

import com.metatron.workforce.phase3.ActorRef;

/** Resolves an external Telegram actor into canonical Metatron identity/context. */
@FunctionalInterface
public interface TelegramIdentityResolver {
    Resolution resolve(long telegramUserId, long telegramChatId);

    record Resolution(
            ActorRef human,
            ActorRef target,
            String organizationContextId) {
        public Resolution {
            if (human == null || human.type() != ActorRef.ActorType.HUMAN) {
                throw new IllegalArgumentException("human must be a HUMAN actor");
            }
            if (target == null || target.type() != ActorRef.ActorType.WORKER) {
                throw new IllegalArgumentException("target must be a WORKER actor");
            }
            if (organizationContextId == null || organizationContextId.isBlank()) {
                throw new IllegalArgumentException("organizationContextId must not be blank");
            }
        }
    }
}
