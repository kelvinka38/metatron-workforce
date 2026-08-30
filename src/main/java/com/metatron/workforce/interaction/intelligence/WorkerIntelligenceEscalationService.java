package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.phase3.Conversation;
import com.metatron.workforce.phase3.Message;
import com.metatron.workforce.phase3.WorkplaceCommunicationService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Allows an institutional Worker to request Intelligence and publish a reference to the result
 * through Workplace-owned communication. The Worker remains the sender/accountable actor;
 * the LLM provider never becomes a Worker or gains institutional authority.
 */
public final class WorkerIntelligenceEscalationService {
    private final IntelligenceFabric fabric;
    private final WorkplaceCommunicationService workplace;
    private final Function<IntelligenceResult, String> artifactPublisher;

    public WorkerIntelligenceEscalationService(
            IntelligenceFabric fabric,
            WorkplaceCommunicationService workplace,
            Function<IntelligenceResult, String> artifactPublisher) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.workplace = Objects.requireNonNull(workplace, "workplace");
        this.artifactPublisher = Objects.requireNonNull(artifactPublisher, "artifactPublisher");
    }

    public EscalationReceipt escalate(
            Conversation conversation,
            ActorRef worker,
            String objective,
            IntelligenceDepth depth,
            CollaborationMode collaborationMode,
            List<LlmProvider> requestedProviders,
            int maxProviders,
            List<String> evidenceReferences) {

        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(collaborationMode, "collaborationMode");
        Objects.requireNonNull(requestedProviders, "requestedProviders");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        if (worker.type() != ActorRef.ActorType.WORKER) {
            throw new IllegalArgumentException("only an institutional Worker may use Worker intelligence escalation");
        }
        if (!conversation.participants().contains(worker)) {
            throw new SecurityException("requesting Worker is not a Workplace conversation participant");
        }
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");

        String requestId = "worker-intelligence-" + UUID.randomUUID();
        IntelligenceRequest request = new IntelligenceRequest(
                requestId,
                "worker:" + worker.actorId(),
                IntelligenceMode.REASONING,
                collaborationMode,
                objective.trim(),
                renderConversationContext(conversation, worker),
                List.copyOf(evidenceReferences),
                "worker.intelligence.escalation",
                IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                latencyBudget(depth),
                costBudget(depth),
                "",
                "evidence-linked intelligence result for accountable Worker review",
                List.copyOf(requestedProviders),
                maxProviders,
                false);

        IntelligenceResult result = fabric.execute(request);
        String contentReference = Objects.requireNonNull(artifactPublisher.apply(result), "artifact reference").trim();
        if (contentReference.isBlank()) throw new IllegalStateException("intelligence artifact publisher returned blank reference");

        // Workplace owns communication and re-evaluates authorization here.
        Message message = workplace.sendMessage(
                conversation.conversationId(),
                worker,
                contentReference,
                conversation.organizationContextId());

        return new EscalationReceipt(
                "conversation:" + conversation.conversationId(),
                "worker:" + worker.actorId(),
                contentReference,
                result,
                message);
    }

    private static String renderConversationContext(Conversation conversation, ActorRef requester) {
        return "WORKPLACE CONVERSATION CONTEXT (owned by Workplace; references only)\n"
                + "conversation_ref=conversation:" + conversation.conversationId() + "\n"
                + "organization_context=" + conversation.organizationContextId() + "\n"
                + "requesting_worker=worker:" + requester.actorId() + "\n"
                + "participants=" + conversation.participants().stream()
                .map(actor -> actor.type().name().toLowerCase(Locale.ROOT) + ":" + actor.actorId()).toList() + "\n"
                + "BOUNDARY: Intelligence may assist the Worker. It does not become sender, participant, authority, assignment owner or execution actor.";
    }

    private static String latencyBudget(IntelligenceDepth depth) {
        return switch (depth) {
            case FAST -> "interactive-fast";
            case ANALYZE -> "interactive-analysis";
            case DEEP -> "extended-investigation";
        };
    }

    private static String costBudget(IntelligenceDepth depth) {
        return switch (depth) {
            case FAST -> "standard";
            case ANALYZE -> "expanded";
            case DEEP -> "deep";
        };
    }

    public record EscalationReceipt(
            String conversationReference,
            String workerReference,
            String intelligenceArtifactReference,
            IntelligenceResult intelligenceResult,
            Message workplaceMessage) {
        public EscalationReceipt {
            Objects.requireNonNull(conversationReference, "conversationReference");
            Objects.requireNonNull(workerReference, "workerReference");
            Objects.requireNonNull(intelligenceArtifactReference, "intelligenceArtifactReference");
            Objects.requireNonNull(intelligenceResult, "intelligenceResult");
            Objects.requireNonNull(workplaceMessage, "workplaceMessage");
        }
    }
}
