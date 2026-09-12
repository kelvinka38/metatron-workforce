package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.FrontierSemanticInterpreter;
import com.metatron.workforce.interaction.intelligence.InstitutionalIntelligenceRuntime;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Semantic admission adapter for natural Human instructions addressed to one already-selected
 * canonical Worker. It reuses the canonical semantic boundary and Objective handoff; it owns no
 * parallel Worker, Objective, Assignment, authorization or execution state.
 */
@Service
public final class WorkerInstructionAdmissionService {
    private final FrontierSemanticInterpreter semanticInterpreter;
    private final ExecutionObjectiveHandoff objectiveHandoff;

    @Autowired
    public WorkerInstructionAdmissionService(
            InstitutionalIntelligenceRuntime intelligenceRuntime,
            ExecutionObjectiveHandoff objectiveHandoff) {
        this(Objects.requireNonNull(intelligenceRuntime, "intelligenceRuntime").semanticInterpreter(),
                objectiveHandoff);
    }

    WorkerInstructionAdmissionService(
            FrontierSemanticInterpreter semanticInterpreter,
            ExecutionObjectiveHandoff objectiveHandoff) {
        this.semanticInterpreter = Objects.requireNonNull(semanticInterpreter, "semanticInterpreter");
        this.objectiveHandoff = Objects.requireNonNull(objectiveHandoff, "objectiveHandoff");
    }

    /**
     * Returns empty for ordinary Worker conversation. A present result is terminal for this Human
     * turn: either clarification/failure or a durable Work admission receipt.
     */
    public Optional<Admission> evaluate(
            MetatronInteraction interaction,
            String workerId,
            String workerContext) {
        Objects.requireNonNull(interaction, "interaction");
        String targetWorkerId = requireText(workerId, "workerId");
        String logicalRef = "worker-instruction:" + interaction.channelProvider() + ":"
                + interaction.externalMessageReference();
        NormalizedRequest normalized;
        try {
            normalized = semanticInterpreter.interpret(
                    interaction.text(),
                    workerContext == null ? "" : workerContext,
                    interaction.channelProvider(),
                    logicalRef,
                    null);
        } catch (RuntimeException failure) {
            String detail = bounded(failure.getMessage());
            return Optional.of(new Admission(
                    "WORKER INSTRUCTION NOT ADMITTED\nreason=SEMANTIC_INTERPRETATION_UNAVAILABLE"
                            + (detail.isBlank() ? "" : "\ndetail=" + detail),
                    logicalRef + ":semantic-failed",
                    List.of(),
                    "",
                    targetWorkerId,
                    false));
        }

        if (normalized.materiallyAmbiguous()) {
            String clarification = normalized.directResponse().isBlank()
                    ? normalized.unresolvedSemanticAmbiguity()
                    : normalized.directResponse();
            return Optional.of(new Admission(
                    clarification,
                    logicalRef + ":clarification",
                    List.of("semantic-provider:" + normalized.semanticProvider()),
                    "",
                    targetWorkerId,
                    false));
        }

        if (normalized.mode() != IntelligenceMode.EXECUTION) return Optional.empty();

        String caseId = "worker-instruction:" + targetWorkerId + ":"
                + Integer.toUnsignedString(Objects.hash(interaction.conversationId(), targetWorkerId), 16);
        ExecutionObjectiveHandoff.HandoffReceipt receipt = objectiveHandoff.submitToWorker(
                targetWorkerId,
                interaction.human().actorId(),
                interaction.organizationContextId(),
                caseId,
                interaction.conversationId(),
                interaction.externalMessageReference(),
                interaction.channelProvider(),
                normalized);

        if (!receipt.accepted()) {
            return Optional.of(new Admission(
                    "WORKER INSTRUCTION BLOCKED\nworker_id=" + targetWorkerId
                            + "\nreason=" + receipt.reason(),
                    logicalRef + ":blocked",
                    List.of(),
                    receipt.objectiveId(),
                    targetWorkerId,
                    false));
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("management-objective:" + receipt.objectiveId());
        if (!receipt.queueItemId().isBlank()) evidence.add("work-queue:" + receipt.queueItemId());
        evidence.add("worker-objective-owner:" + targetWorkerId);
        String text = "🧰 WORK · WORKER\n"
                + "worker_id=" + targetWorkerId
                + "\nobjective_id=" + receipt.objectiveId()
                + "\nobjective_status=" + receipt.objectiveStatus()
                + "\nexecution_state=" + receipt.executionAdmissionState()
                + "\nreason=" + receipt.reason();
        return Optional.of(new Admission(
                text,
                logicalRef + ":accepted:" + receipt.objectiveId(),
                List.copyOf(evidence),
                receipt.objectiveId(),
                targetWorkerId,
                true));
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 240 ? clean : clean.substring(0, 240);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    public record Admission(
            String text,
            String requestReference,
            List<String> evidenceReferences,
            String objectiveId,
            String workerId,
            boolean admitted) {
        public Admission {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requestReference, "requestReference");
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            objectiveId = objectiveId == null ? "" : objectiveId;
            workerId = requireText(workerId, "workerId");
        }
    }
}
