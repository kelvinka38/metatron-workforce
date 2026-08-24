package com.metatron.workforce.adapter.telegram;

import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.AuthorizationContext;
import com.metatron.workforce.phase3.WorkplaceCommunicationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TelegramWorkplaceAdapterTest {
    private final ActorRef human = new ActorRef("HUMAN-001", ActorRef.ActorType.HUMAN);
    private final ActorRef worker = new ActorRef("WORKER-001", ActorRef.ActorType.WORKER);
    private final WorkplaceCommunicationService workplace = new WorkplaceCommunicationService(
            (actor, target, org) -> AuthorizationContext.allowed("AUTH-001"),
            Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    private final TelegramWorkplaceAdapter adapter = new TelegramWorkplaceAdapter(
            workplace,
            (telegramUserId, telegramChatId) -> new TelegramIdentityResolver.Resolution(
                    human, worker, "ORG-001"));

    @Test
    void acceptedUpdateBecomesAttributableWorkplaceMessage() {
        TelegramUpdate update = new TelegramUpdate(100L,
                new TelegramUpdate.TelegramMessage(7L,
                        new TelegramUpdate.TelegramChat(900L, "private"),
                        new TelegramUpdate.TelegramUser(42L, false, "Kelvin", "kelvin"),
                        "audit production gateway"));

        TelegramWorkplaceAdapter.Result result = adapter.accept(update);

        assertEquals(TelegramWorkplaceAdapter.Status.ACCEPTED, result.status());
        assertEquals("audit production gateway", result.text());
        assertNotNull(result.message());
        assertEquals("HUMAN-001", result.message().sender().actorId());
        assertEquals("ORG-001", result.message().organizationContextId());
        assertEquals("AUTH-001", result.message().authorizationId());
        assertEquals(1, workplace.conversations().size());
        assertEquals(1, workplace.messages().size());
    }

    @Test
    void duplicateTelegramRetryIsIgnored() {
        TelegramUpdate update = update(101L);

        assertEquals(TelegramWorkplaceAdapter.Status.ACCEPTED, adapter.accept(update).status());
        TelegramWorkplaceAdapter.Result retry = adapter.accept(update);

        assertEquals(TelegramWorkplaceAdapter.Status.DUPLICATE, retry.status());
        assertEquals(1, workplace.messages().size());
    }

    @Test
    void botMessagesAreNotHumanCommunication() {
        TelegramUpdate update = new TelegramUpdate(102L,
                new TelegramUpdate.TelegramMessage(8L,
                        new TelegramUpdate.TelegramChat(900L, "private"),
                        new TelegramUpdate.TelegramUser(99L, true, "bot", "bot"),
                        "execute"));

        assertEquals(TelegramWorkplaceAdapter.Status.IGNORED, adapter.accept(update).status());
        assertTrue(workplace.messages().isEmpty());
    }

    @Test
    void blankMessagesAreIgnored() {
        TelegramUpdate update = new TelegramUpdate(103L,
                new TelegramUpdate.TelegramMessage(9L,
                        new TelegramUpdate.TelegramChat(900L, "private"),
                        new TelegramUpdate.TelegramUser(42L, false, "Kelvin", "kelvin"),
                        "   "));

        assertEquals(TelegramWorkplaceAdapter.Status.IGNORED, adapter.accept(update).status());
        assertTrue(workplace.messages().isEmpty());
    }

    private static TelegramUpdate update(long updateId) {
        return new TelegramUpdate(updateId,
                new TelegramUpdate.TelegramMessage(updateId,
                        new TelegramUpdate.TelegramChat(900L, "private"),
                        new TelegramUpdate.TelegramUser(42L, false, "Kelvin", "kelvin"),
                        "audit"));
    }
}
