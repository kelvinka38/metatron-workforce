package com.metatron.workforce.interaction;

import com.metatron.workforce.phase3.ActorRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ChannelInteractionIngressServiceTest {
    @Test
    void telegramAndZaloUseSameCanonicalConversationIngress() {
        List<MetatronInteraction> observed = new ArrayList<>();
        MetatronInteractionOrchestrator fakeCore = new MetatronInteractionOrchestrator(interaction -> {
            observed.add(interaction);
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(),
                    "ok:" + interaction.channelProvider(),
                    "test:" + interaction.externalMessageReference());
        });
        ChannelInteractionIngressService ingress = new ChannelInteractionIngressService(fakeCore);

        ActorRef human = new ActorRef("human-primary", ActorRef.ActorType.HUMAN);
        ActorRef head = new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER);
        String canonicalConversation = "conversation:human:human-primary";

        MetatronInteraction telegram = new MetatronInteraction(
                human, head, "organization:metatron", canonicalConversation,
                "telegram", "telegram:user:100", "telegram:chat:200", "telegram:update:300",
                "Audit the workforce");
        MetatronInteraction zalo = new MetatronInteraction(
                human, head, "organization:metatron", canonicalConversation,
                "zalo", "zalo:user:abc", "zalo:conversation:def", "zalo:message:ghi",
                "Continue the audit");

        assertEquals("ok:telegram", ingress.handle(telegram).text());
        assertEquals("ok:zalo", ingress.handle(zalo).text());
        assertEquals(2, observed.size());
        assertEquals(human, observed.get(0).human());
        assertEquals(human, observed.get(1).human());
        assertEquals(canonicalConversation, observed.get(0).conversationId());
        assertEquals(canonicalConversation, observed.get(1).conversationId());

        assertEquals("telegram", observed.get(0).channelProvider());
        assertEquals("zalo", observed.get(1).channelProvider());
        assertEquals("telegram:user:100", observed.get(0).externalActorReference());
        assertEquals("zalo:user:abc", observed.get(1).externalActorReference());
        assertEquals("telegram:chat:200", observed.get(0).externalConversationReference());
        assertEquals("zalo:conversation:def", observed.get(1).externalConversationReference());
        assertEquals("telegram:update:300", observed.get(0).externalMessageReference());
        assertEquals("zalo:message:ghi", observed.get(1).externalMessageReference());

        assertNotEquals(observed.get(0).externalActorReference(), observed.get(0).human().actorId());
        assertNotEquals(observed.get(1).externalActorReference(), observed.get(1).human().actorId());
        assertNotEquals(observed.get(0).externalConversationReference(), observed.get(0).conversationId());
        assertNotEquals(observed.get(1).externalConversationReference(), observed.get(1).conversationId());
        assertNotEquals(observed.get(0).externalMessageReference(), observed.get(0).conversationId());
        assertNotEquals(observed.get(1).externalMessageReference(), observed.get(1).conversationId());
    }
}
