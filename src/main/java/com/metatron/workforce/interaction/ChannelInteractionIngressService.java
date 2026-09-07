package com.metatron.workforce.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.IntelligenceCaseStore;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepthControlService;
import com.metatron.workforce.interaction.intelligence.InstitutionalIntelligenceRuntime;
import com.metatron.workforce.interaction.intelligence.MetatronIntelligenceResponder;
import com.metatron.workforce.interaction.memory.PersistentConversationMemoryStore;
import com.metatron.workforce.workplace.WorkplaceMeetingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One canonical ingress from normalized Human communication into Metatron Conversation/Interaction.
 *
 * Channel providers are adapters only. Telegram, Zalo, Web, API or another approved provider must
 * authenticate/map their external identity and then submit a normalized {@link MetatronInteraction}
 * here. No provider owns intelligence, Workplace, conversation memory, depth control or execution orchestration.
 */
@Service
public final class ChannelInteractionIngressService {
    private static final int MEMORY_MAX_TURNS = 32;
    private static final int MEMORY_MAX_CHARS = 32000;

    private final MetatronInteractionOrchestrator orchestrator;

    @Autowired
    public ChannelInteractionIngressService(
            @Value("${METATRON_LLM_PROVIDER:AUTO}") String provider,
            InstitutionalIntelligenceRuntime intelligenceRuntime,
            @Value("${METATRON_GATEWAY_AUDIT_URL:}") String gatewayAuditUrl,
            @Value("${METATRON_GATEWAY_AUDIT_TOKEN:}") String gatewayAuditToken,
            IntelligenceCaseStore intelligenceCaseStore,
            IntelligenceDepthControlService intelligenceDepthControlService,
            ExecutionObjectiveHandoff executionObjectiveHandoff,
            WorkplaceMeetingService workplaceMeetingService,
            MetatronInstitutionalStateChatService institutionalStateChatService,
            @Value("${METATRON_CONVERSATION_MEMORY_PATH:${METATRON_TELEGRAM_MEMORY_PATH:/var/lib/metatron-workforce/conversations}}") String conversationMemoryPath,
            @Value("${METATRON_CONVERSATION_SURFACE_MODE_PATH:/var/lib/metatron-workforce/conversation-surface-mode}") String conversationSurfaceModePath,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        MetatronIntelligenceResponder intelligence = new MetatronIntelligenceResponder(
                Objects.requireNonNull(intelligenceRuntime, "intelligenceRuntime"),
                provider, objectMapper, gatewayAuditUrl, gatewayAuditToken,
                Objects.requireNonNull(intelligenceCaseStore, "intelligenceCaseStore"),
                Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff"),
                semanticChatExecutionHandoffEnabled());
        MetatronConversationRuntime conversationRuntime = new MetatronConversationRuntime(
                new PersistentConversationMemoryStore(Path.of(conversationMemoryPath), objectMapper),
                intelligence,
                Objects.requireNonNull(intelligenceDepthControlService, "intelligenceDepthControlService"),
                Objects.requireNonNull(workplaceMeetingService, "workplaceMeetingService"),
                new ConversationSurfaceModeService(new PersistentConversationSurfaceModeStore(Path.of(conversationSurfaceModePath))),
                Objects.requireNonNull(institutionalStateChatService, "institutionalStateChatService"),
                MEMORY_MAX_TURNS,
                MEMORY_MAX_CHARS);
        this.orchestrator = new MetatronInteractionOrchestrator(conversationRuntime::handle);
    }

    /**
     * Compatibility hook for package-level tests and adapters. The configured Workforce handoff is
     * retained only for typed/explicit institutional transitions. Ordinary semantic chat is never
     * authorized to materialize durable Work from this channel ingress.
     */
    static boolean semanticChatExecutionHandoffEnabled() { return false; }

    static ExecutionObjectiveHandoff channelExecutionHandoff(
            boolean enabled,
            ExecutionObjectiveHandoff configuredHandoff) {
        Objects.requireNonNull(configuredHandoff, "configuredHandoff");
        return configuredHandoff;
    }

    ChannelInteractionIngressService(MetatronInteractionOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public MetatronInteractionOrchestrator.InteractionResponse handle(MetatronInteraction interaction) {
        return orchestrator.handle(Objects.requireNonNull(interaction, "interaction"));
    }
}
