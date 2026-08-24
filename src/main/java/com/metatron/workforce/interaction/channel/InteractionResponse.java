package com.metatron.workforce.interaction.channel;

import java.util.Objects;

public record InteractionResponse(String recipientId, String text) {
    public InteractionResponse {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(text, "text");
    }
}
