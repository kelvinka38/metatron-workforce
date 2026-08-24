package com.metatron.workforce.adapter.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Minimal Telegram Update representation required by the workplace adapter. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        TelegramMessage message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramMessage(
            @JsonProperty("message_id") long messageId,
            TelegramChat chat,
            TelegramUser from,
            String text) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramChat(
            long id,
            String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelegramUser(
            long id,
            @JsonProperty("is_bot") boolean bot,
            @JsonProperty("first_name") String firstName,
            String username) {}
}
