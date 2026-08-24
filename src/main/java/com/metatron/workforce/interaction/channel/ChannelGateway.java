package com.metatron.workforce.interaction.channel;

public interface ChannelGateway {
    String channel();
    String send(ChannelMessage message);
}
