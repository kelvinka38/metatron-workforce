package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.intelligence.IntelligenceDepthContract;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepthControlService;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;
import com.metatron.workforce.interaction.memory.ConversationMemoryStore;
import com.metatron.workforce.workplace.WorkplaceMeetingService;

import java.util.Objects;

/** Canonical channel-independent conversational runtime. Every human channel enters here after transport normalization. */
public final class MetatronConversationRuntime {
    private final ConversationMemoryStore memory;
    private final MetatronIntelligenceResponder intelligence;
    private final IntelligenceDepthControlService depthControl;
    private final WorkplaceMeetingService meetingRoom;
    private final ConversationSurfaceModeService surfaceMode;
    private final MetatronInstitutionalStateChatService institutionalStateChat;
    private final int maxTurns;
    private final int maxChars;

    /** Backward-compatible composition: semantic AUTO depth when no explicit control service is supplied. */
    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       int maxTurns,
                                       int maxChars) {
        this(memory, intelligence, null, null, null, null, maxTurns, maxChars);
    }

    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       IntelligenceDepthControlService depthControl,
                                       int maxTurns,
                                       int maxChars) {
        this(memory, intelligence, depthControl, null, null, null, maxTurns, maxChars);
    }

    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       IntelligenceDepthControlService depthControl,
                                       WorkplaceMeetingService meetingRoom,
                                       int maxTurns,
                                       int maxChars) {
        this(memory, intelligence, depthControl, meetingRoom, null, null, maxTurns, maxChars);
    }

    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       IntelligenceDepthControlService depthControl,
                                       WorkplaceMeetingService meetingRoom,
                                       ConversationSurfaceModeService surfaceMode,
                                       int maxTurns,
                                       int maxChars) {
        this(memory, intelligence, depthControl, meetingRoom, surfaceMode, null, maxTurns, maxChars);
    }

    public MetatronConversationRuntime(ConversationMemoryStore memory,
                                       MetatronIntelligenceResponder intelligence,
                                       IntelligenceDepthControlService depthControl,
                                       WorkplaceMeetingService meetingRoom,
                                       ConversationSurfaceModeService surfaceMode,
                                       MetatronInstitutionalStateChatService institutionalStateChat,
                                       int maxTurns,
                                       int maxChars) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
        this.depthControl = depthControl;
        this.meetingRoom = meetingRoom;
        this.surfaceMode = surfaceMode;
        this.institutionalStateChat = institutionalStateChat;
        if (maxTurns < 1 || maxChars < 1) throw new IllegalArgumentException("memory limits must be positive");
        this.maxTurns = maxTurns;
        this.maxChars = maxChars;
    }

    /** Canonical entrypoint: provider identity is transport metadata carried by the normalized interaction. */
    public MetatronInteractionOrchestrator.InteractionResponse handle(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        String channel = interaction.channelProvider();
        if (channel.isBlank()) throw new IllegalArgumentException("channelProvider must not be blank");

        if (surfaceMode != null) {
            ConversationSurfaceModeService.ControlResult surfaceControl = surfaceMode.handle(
                    interaction.conversationId(), interaction.text());
            if (surfaceControl.handled()) {
                memory.appendTurn(interaction.conversationId(), interaction.text(), surfaceControl.response());
                return new MetatronInteractionOrchestrator.InteractionResponse(
                        interaction.conversationId(), surfaceControl.response(),
                        "conversation-surface-control:" + interaction.externalMessageReference());
            }
        }

        if (depthControl != null) {
            IntelligenceDepthControlService.ControlResult control = depthControl.handle(
                    interaction.conversationId(), interaction.text());
            if (control.controlHandled()) {
                memory.appendTurn(interaction.conversationId(), interaction.text(), control.response());
                return new MetatronInteractionOrchestrator.InteractionResponse(
                        interaction.conversationId(), control.response(),
                        "intelligence-depth-control:" + interaction.externalMessageReference());
            }
        }

        String history = memory.contextFor(
                interaction.conversationId(), interaction.text(), maxTurns,
                Math.max(4, maxTurns / 4), maxChars);

        // Product hierarchy: Chat and Work are top-level surfaces. Meeting lives inside Work.
        ConversationSurfaceMode selectedSurface = surfaceMode == null
                ? ConversationSurfaceMode.CHAT
                : surfaceMode.mode(interaction.conversationId());
        boolean workSurfaceSelected = selectedSurface == ConversationSurfaceMode.WORK
                || selectedSurface == ConversationSurfaceMode.WORK_MEETING;
        boolean meetingModuleSelected = selectedSurface == ConversationSurfaceMode.WORK_MEETING;

        if (selectedSurface == ConversationSurfaceMode.CHAT && institutionalStateChat != null) {
            java.util.Optional<String> institutionalAnswer = institutionalStateChat.answer(interaction.text());
            if (institutionalAnswer.isPresent()) {
                String answer = institutionalAnswer.get();
                memory.appendTurn(interaction.conversationId(), interaction.text(), answer);
                return new MetatronInteractionOrchestrator.InteractionResponse(
                        interaction.conversationId(), answer,
                        "chat:canonical-institutional-state:" + interaction.externalMessageReference());
            }
        }

        if (meetingRoom != null && workSurfaceSelected && WorkplaceMeetingService.isAuthorizedFollowUp(interaction.text())) {
            String meetingAnswer = meetingRoom.handle(interaction, history);
            memory.appendTurn(interaction.conversationId(), interaction.text(), meetingAnswer);
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(), meetingAnswer, "work:meeting-follow-up");
        }

        if (meetingRoom != null && meetingModuleSelected && !meetingRoom.supportsInMeetingMode(interaction.text())) {
            String answer = "🧰 WORK · MEETING\nDescribe the meeting naturally and include the institutional roles involved. "
                    + "Example: Head of Technology and Head of Gateway discuss creating the Gateway leadership role.";
            memory.appendTurn(interaction.conversationId(), interaction.text(), answer);
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(), answer,
                    "work:meeting-guidance:" + interaction.externalMessageReference());
        }

        if (meetingRoom != null && meetingModuleSelected && meetingRoom.supportsInMeetingMode(interaction.text())) {
            String meetingAnswer = meetingRoom.handle(interaction, history);
            String answer = meetingAnswer;
            memory.appendTurn(interaction.conversationId(), interaction.text(), answer);
            MeetingRoomReference reference = meetingRoom.findByExternalMessageReference(interaction.externalMessageReference())
                    .map(m -> new MeetingRoomReference(m.meetingId()))
                    .orElse(new MeetingRoomReference("meeting:unresolved"));
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(), answer, reference.meetingId());
        }

        if (workSurfaceSelected) {
            String answer = "🧰 WORK · METATRON\nChoose a Work module: 🏛 Meeting or 📊 Monitor. "
                    + "Work commands stay out of normal Chat and do not silently create Objectives.";
            memory.appendTurn(interaction.conversationId(), interaction.text(), answer);
            return new MetatronInteractionOrchestrator.InteractionResponse(
                    interaction.conversationId(), answer,
                    "work:module-menu:" + interaction.externalMessageReference());
        }

        IntelligenceDepthContract contract = depthControl == null
                ? IntelligenceDepthContract.automatic()
                : depthControl.contract(interaction.conversationId());
        String rawAnswer = intelligence.respond(
                interaction.human().actorId(), interaction.text(), interaction.externalMessageReference(),
                channel, interaction.conversationId(), interaction.organizationContextId(), history, contract);
        // Depth remains an internal execution preference until differentiated mode flows are completed.
        // Do not surface FAST/ANALYZE/DEEP labels in the Chat product UI.
        String answer = rawAnswer;
        memory.appendTurn(interaction.conversationId(), interaction.text(), answer);

        return new MetatronInteractionOrchestrator.InteractionResponse(
                interaction.conversationId(), answer,
                "interaction:" + interaction.externalMessageReference());
    }

    /** Compatibility entrypoint for older callers that supplied transport separately. */
    @Deprecated
    public MetatronInteractionOrchestrator.InteractionResponse handle(MetatronInteraction interaction, String channel) {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(channel, "channel");
        MetatronInteraction normalized = new MetatronInteraction(
                interaction.human(), interaction.target(), interaction.organizationContextId(),
                interaction.conversationId(), channel,
                interaction.externalActorReference(), interaction.externalConversationReference(),
                interaction.externalMessageReference(), interaction.text());
        return handle(normalized);
    }

    private record MeetingRoomReference(String meetingId) {}
}
