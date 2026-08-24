package com.metatron.workforce.adapter.telegram;

import com.metatron.workforce.phase3.Conversation;
import com.metatron.workforce.phase3.Message;
import com.metatron.workforce.phase3.WorkplaceCommunicationService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transport adapter from Telegram updates into the frozen Phase 3 workplace contract.
 * It does not authorize or execute work and never calls Gateway directly.
 */
public final class TelegramWorkplaceAdapter {
    private final WorkplaceCommunicationService workplace;
    private final TelegramIdentityResolver identities;
    private final Set<Long> processedUpdates = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, String> conversationsByChat = new ConcurrentHashMap<>();

    public TelegramWorkplaceAdapter(
            WorkplaceCommunicationService workplace,
            TelegramIdentityResolver identities) {
        this.workplace = workplace;
        this.identities = identities;
    }

    public synchronized Result accept(TelegramUpdate update) {
        if (update == null || update.message() == null) {
            return Result.ignored("update has no message");
        }
        TelegramUpdate.TelegramMessage message = update.message();
        if (message.from() == null || message.chat() == null) {
            return Result.ignored("message has no sender or chat");
        }
        if (message.from().bot()) {
            return Result.ignored("bot-originated messages are not accepted as Human communication");
        }
        if (message.text() == null || message.text().isBlank()) {
            return Result.ignored("message has no text content");
        }
        if (processedUpdates.contains(update.updateId())) {
            return Result.duplicate(update.updateId());
        }

        TelegramIdentityResolver.Resolution identity = identities.resolve(
                message.from().id(), message.chat().id());

        Conversation conversation = conversation(identity, message.chat().id());
        String contentReference = "telegram://chat/" + message.chat().id() + "/message/" + message.messageId();
        Message materialized = workplace.sendMessage(
                conversation.conversationId(),
                identity.human(),
                contentReference,
                identity.organizationContextId());

        processedUpdates.add(update.updateId());
        return Result.accepted(update.updateId(), materialized, message.text());
    }

    private Conversation conversation(TelegramIdentityResolver.Resolution identity, long chatId) {
        final String existing = conversationsByChat.get(chatId);
        if (existing != null) {
            return workplace.conversations().stream()
                    .filter(item -> item.conversationId().equals(existing))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("mapped Telegram conversation is missing"));
        }

        Conversation created = workplace.startConversation(
                identity.human(),
                List.of(identity.human(), identity.target()),
                identity.organizationContextId());
        conversationsByChat.put(chatId, created.conversationId());
        return created;
    }

    public record Result(
            Status status,
            long updateId,
            String reason,
            Message message,
            String text) {
        static Result accepted(long updateId, Message message, String text) {
            return new Result(Status.ACCEPTED, updateId, null, message, text);
        }
        static Result duplicate(long updateId) {
            return new Result(Status.DUPLICATE, updateId, "update already processed", null, null);
        }
        static Result ignored(String reason) {
            return new Result(Status.IGNORED, -1, reason, null, null);
        }
    }

    public enum Status { ACCEPTED, DUPLICATE, IGNORED }
}
