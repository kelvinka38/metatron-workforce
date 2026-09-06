package com.metatron.workforce.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.IntelligenceCaseStore;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepthControlService;
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
            @Value("${OPENAI_API_KEY:}") String openAiApiKey,
            @Value("${GEMINI_API_KEY:}") String googleApiKey,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${METATRON_LLM_PROVIDER:AUTO}") String provider,
            @Value("${OPENAI_MODEL:}") String openAiModel,
            @Value("${GEMINI_MODEL:}") String googleModel,
            @Value("${ANTHROPIC_MODEL:}") String anthropicModel,
            @Value("${METATRON_GATEWAY_AUDIT_URL:}") String gatewayAuditUrl,
            @Value("${METATRON_GATEWAY_AUDIT_TOKEN:}") String gatewayAuditToken,
            @Value("${METATRON_CHANNEL_OBJECTIVE_HANDOFF_ENABLED:false}") boolean channelObjectiveHandoffEnabled,
            IntelligenceCaseStore intelligenceCaseStore,
            IntelligenceDepthControlService intelligenceDepthControlService,
            ExecutionObjectiveHandoff executionObjectiveHandoff,
            WorkplaceMeetingService workplaceMeetingService,
            @Value("${METATRON_CONVERSATION_MEMORY_PATH:${METATRON_TELEGRAM_MEMORY_PATH:/var/lib/metatron-workforce/conversations}}") String conversationMemoryPath,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        MetatronIntelligenceResponder intelligence = new MetatronIntelligenceResponder(
                openAiApiKey, googleApiKey, anthropicApiKey, provider,
                openAiModel, googleModel, anthropicModel, objectMapper,
                gatewayAuditUrl, gatewayAuditToken,
                Objects.requireNonNull(intelligenceCaseStore, "intelligenceCaseStore"),
                channelExecutionHandoff(channelObjectiveHandoffEnabled, executionObjectiveHandoff));
        MetatronConversationRuntime conversationRuntime = new MetatronConversationRuntime(
                new PersistentConversationMemoryStore(Path.of(conversationMemoryPath), objectMapper),
                intelligence,
                Objects.requireNonNull(intelligenceDepthControlService, "intelligenceDepthControlService"),
                Objects.requireNonNull(workplaceMeetingService, "workplaceMeetingService"),
                MEMORY_MAX_TURNS,
                MEMORY_MAX_CHARS);
        this.orchestrator = new MetatronInteractionOrchestrator(conversationRuntime::handle);
    }

    static ExecutionObjectiveHandoff channelExecutionHandoff(
            boolean enabled,
            ExecutionObjectiveHandoff configuredHandoff) {
        Objects.requireNonNull(configuredHandoff, "configuredHandoff");
        return enabled ? configuredHandoff : ExecutionObjectiveHandoff.unavailable();
    }

    ChannelInteractionIngressService(MetatronInteractionOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public MetatronInteractionOrchestrator.InteractionResponse handle(MetatronInteraction interaction) {
        return orchestrator.handle(Objects.requireNonNull(interaction, "interaction"));
    }
}
