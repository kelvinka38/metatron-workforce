package com.metatron.workforce.interaction.channel;

import java.util.Objects;

/** Channel-neutral Telegram webhook boundary. Transport/security validation is performed by the host adapter. */
public final class TelegramWebhookAdapter {
    private final String secretToken;

    public TelegramWebhookAdapter(String secretToken) {
        this.secretToken = Objects.requireNonNull(secretToken, "secretToken");
    }

    public ChannelMessage receive(String suppliedSecret, String senderId, String text) {
        if (!secretToken.equals(suppliedSecret)) throw new SecurityException("telegram_secret_invalid");
        if (senderId == null || senderId.isBlank() || text == null || text.isBlank())
            throw new IllegalArgumentException("telegram_message_invalid");
        return new ChannelMessage("telegram", senderId, text);
    }
}
