package com.metatron.workforce.interaction.channel;

import java.util.Objects;

public record ChannelMessage(String channel, String senderId, String text) {
    public ChannelMessage {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(text, "text");
    }
}
