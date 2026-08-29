package com.metatron.workforce.interaction.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;

/**
 * Compatibility wrapper for the Telegram transport.
 * Canonical intelligence is channel-neutral and lives in MetatronIntelligenceResponder.
 */
@Deprecated
public final class TelegramIntelligenceResponder {
    private final MetatronIntelligenceResponder delegate;

    public TelegramIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper) {
        this.delegate = new MetatronIntelligenceResponder(openAiApiKey, googleApiKey, anthropicApiKey,
                provider, openAiModel, googleModel, anthropicModel, objectMapper);
    }

    public TelegramIntelligenceResponder(String openAiApiKey, String googleApiKey, String anthropicApiKey,
                                         String provider, String openAiModel, String googleModel,
                                         String anthropicModel, ObjectMapper objectMapper,
                                         String gatewayAuditUrl, String gatewayAuditToken) {
        this.delegate = new MetatronIntelligenceResponder(openAiApiKey, googleApiKey, anthropicApiKey,
                provider, openAiModel, googleModel, anthropicModel, objectMapper,
                gatewayAuditUrl, gatewayAuditToken);
    }

    public String respond(String senderId, String text) {
        return delegate.respond(senderId, text, "telegram-message", "telegram", "");
    }

    public String respond(String senderId, String text, String externalMessageReference) {
        return delegate.respond(senderId, text, externalMessageReference, "telegram", "");
    }

    public String respond(String senderId, String text, String externalMessageReference, String conversationContext) {
        return delegate.respond(senderId, text, externalMessageReference, "telegram", conversationContext);
    }
}
