package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.memory.PersistentWorkerConversationMemoryStore;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.workplace.MeetingWorkerDirectory;
import com.metatron.workforce.workplace.WorkerConversationGateway;
import com.metatron.workforce.workplace.WorkplaceControlRoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Channel-neutral Human↔Worker conversation product.
 *
 * A channel may select a canonical Worker, but never owns the Worker identity, runtime, memory,
 * cognition, authority or execution semantics. The active binding is durable per canonical
 * Conversation; memory is durable per canonical Human + Worker and therefore survives channel
 * switches between Telegram, Workplace and future approved adapters.
 */
@Service
public final class DirectWorkerConversationService {
    public static final String WORKERS_CONTROL = "👥 Workers";
    private static final int MAX_RECENT_TURNS = 24;
    private static final int MAX_RELEVANT_TURNS = 12;
    private static final int MAX_MEMORY_CHARS = 18_000;

    private final DirectWorkerConversationBindingStore bindings;
    private final PersistentWorkerConversationMemoryStore memory;
    private final WorkplaceControlRoomService controlRoom;
    private final MeetingWorkerDirectory directory;
    private final WorkerConversationGateway workerConversation;
    private final WorkerInstructionAdmissionService instructionAdmission;

    @Autowired
    public DirectWorkerConversationService(
            DirectWorkerConversationBindingStore bindings,
            PersistentWorkerConversationMemoryStore memory,
            WorkplaceControlRoomService controlRoom,
            MeetingWorkerDirectory directory,
            WorkerConversationGateway workerConversation,
            WorkerInstructionAdmissionService instructionAdmission) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.controlRoom = Objects.requireNonNull(controlRoom, "controlRoom");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.workerConversation = Objects.requireNonNull(workerConversation, "workerConversation");
        this.instructionAdmission = Objects.requireNonNull(instructionAdmission, "instructionAdmission");
    }

    DirectWorkerConversationService(
            DirectWorkerConversationBindingStore bindings,
            PersistentWorkerConversationMemoryStore memory,
            WorkplaceControlRoomService controlRoom,
            MeetingWorkerDirectory directory,
            WorkerConversationGateway workerConversation) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.controlRoom = Objects.requireNonNull(controlRoom, "controlRoom");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.workerConversation = Objects.requireNonNull(workerConversation, "workerConversation");
        this.instructionAdmission = null;
    }

    public Optional<HandledReply> handle(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        String text = interaction.text().trim();

        if (isSurfaceControl(text)) {
            bindings.clear(interaction.conversationId());
            return Optional.empty();
        }

        Optional<String> activeBinding = bindings.workerId(interaction.conversationId());
        if (isExit(text)) {
            if (activeBinding.isPresent()) bindings.clear(interaction.conversationId());
            if (activeBinding.isPresent() || isExplicitExitCommand(text)) {
                return Optional.of(new HandledReply(
                        "💬 METATRON\n"
                                + (activeBinding.isPresent()
                                ? "Direct Worker conversation closed. "
                                : "No Direct Worker is currently selected. ")
                                + "You are talking to Metatron.",
                        "direct-worker:exit:" + interaction.externalMessageReference(),
                        ""));
            }
        }

        if (isDirectory(text)) {
            return Optional.of(new HandledReply(
                    renderDirectory(),
                    "direct-worker:directory:" + interaction.externalMessageReference(),
                    ""));
        }

        Optional<String> selection = selectionQuery(text);
        if (selection.isPresent()) {
            WorkplaceControlRoomService.WorkerDetail worker = resolveAndPrepare(selection.get());
            bindings.bind(interaction.conversationId(), worker.workerId());
            int rememberedTurns = memory.turnCount(interaction.human().actorId(), worker.workerId());
            return Optional.of(new HandledReply(
                    "👤 WORKER · " + humanRole(worker) + "\n"
                            + "worker_id=" + worker.workerId() + "\n"
                            + "runtime_state=" + worker.runtimeState() + "\n"
                            + "memory_turns=" + rememberedTurns + "\n\n"
                            + "You are now talking directly with this canonical Worker. "
                            + "Continue naturally; prior Worker memory is loaded automatically. "
                            + "Use /metatron to leave this Worker.",
                    "direct-worker:bound:" + worker.workerId(),
                    worker.workerId()));
        }

        if (activeBinding.isEmpty()) return Optional.empty();

        try {
            WorkplaceControlRoomService.WorkerDetail detail =
                    controlRoom.prepareConversationWorker(activeBinding.get());
            Optional<ConversationReply> admitted = admitInstruction(interaction, detail);
            if (admitted.isPresent()) {
                ConversationReply reply = admitted.get();
                return Optional.of(new HandledReply(
                        reply.turn().worker(), reply.turn().requestReference(), reply.workerId()));
            }
            ConversationReply reply = conversePrepared(
                    interaction.human().actorId(),
                    detail,
                    interaction.text(),
                    interaction.channelProvider());
            return Optional.of(new HandledReply(
                    reply.turn().worker(),
                    "direct-worker:" + reply.workerId() + ":" + reply.turn().requestReference(),
                    reply.workerId()));
        } catch (IllegalStateException unavailable) {
            bindings.clear(interaction.conversationId());
            return Optional.of(new HandledReply(
                    "Direct Worker conversation was closed because the bound Worker is no longer operational: "
                            + unavailable.getMessage(),
                    "direct-worker:unavailable:" + interaction.externalMessageReference(),
                    ""));
        }
    }

    public ConversationReply converse(String humanId, String requestedWorkerId, String message, String channel) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message required");
        WorkplaceControlRoomService.WorkerDetail detail =
                controlRoom.prepareConversationWorker(requestedWorkerId);
        return conversePrepared(humanId, detail, message, channel);
    }

    /**
     * Product-neutral selected-Worker entry used by Workplace and future direct Worker surfaces.
     * It first applies the same semantic Work-admission boundary used by channel bindings; ordinary
     * conversation falls through to Worker cognition without creating durable Work.
     */
    public ConversationReply converseOrAdmit(
            String humanId,
            String requestedWorkerId,
            String message,
            String channel,
            String externalMessageReference) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message required");
        WorkplaceControlRoomService.WorkerDetail detail =
                controlRoom.prepareConversationWorker(requestedWorkerId);
        String workerId = detail.workerId();
        String organizationContextId = detail.participations().stream()
                .filter(participation -> participation.status()
                        == com.metatron.workforce.core.WorkforceCoreService.ParticipationStatus.ACTIVE)
                .map(com.metatron.workforce.core.WorkforceCoreService.Participation::organizationRef)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("worker_has_no_active_organization:" + workerId));
        String conversationId = "conversation:" + requireToken(channel, "channel") + ":"
                + requireToken(humanId, "humanId") + ":" + workerId;
        String messageRef = requireToken(externalMessageReference, "externalMessageReference");
        MetatronInteraction interaction = new MetatronInteraction(
                new ActorRef(humanId, ActorRef.ActorType.HUMAN),
                new ActorRef(workerId, ActorRef.ActorType.WORKER),
                organizationContextId,
                conversationId,
                channel,
                channel + ":human:" + humanId,
                channel + ":worker:" + workerId,
                messageRef,
                message.trim());
        return admitInstruction(interaction, detail)
                .orElseGet(() -> conversePrepared(humanId, detail, message, channel));
    }

    private ConversationReply conversePrepared(
            String humanId,
            WorkplaceControlRoomService.WorkerDetail detail,
            String message,
            String channel) {
        String workerId = detail.workerId();

        String memoryContext = memory.contextFor(
                humanId, workerId, message,
                MAX_RECENT_TURNS, MAX_RELEVANT_TURNS, MAX_MEMORY_CHARS);
        String context = workerOperationalContext(detail)
                + (memoryContext.isBlank() ? "" : "\n\nDURABLE WORKER MEMORY\n" + memoryContext);

        List<String> trustedExecutionEvidence = detail.actions().stream()
                .flatMap(action -> action.evidenceReferences().stream())
                .filter(ref -> ref != null && ref.startsWith("action-fabric:"))
                .filter(ref -> ref.contains(":worker=" + workerId + ":"))
                .filter(ref -> ref.endsWith(":success=true") || ref.contains(":success=true:"))
                .distinct()
                .limit(200)
                .toList();

        WorkerConversationGateway.Reply reply = workerConversation.converse(
                workerId,
                detail.primaryRole(),
                message.trim(),
                context,
                trustedExecutionEvidence);

        PersistentWorkerConversationMemoryStore.Turn turn = memory.append(
                humanId,
                workerId,
                channel,
                message,
                reply.text(),
                reply.requestReference(),
                reply.runtimeId(),
                reply.evidenceReferences());
        return new ConversationReply(workerId, detail, turn);
    }

    private Optional<ConversationReply> admitInstruction(
            MetatronInteraction interaction,
            WorkplaceControlRoomService.WorkerDetail detail) {
        if (instructionAdmission == null) return Optional.empty();
        String memoryContext = memory.contextFor(
                interaction.human().actorId(), detail.workerId(), interaction.text(),
                MAX_RECENT_TURNS, MAX_RELEVANT_TURNS, MAX_MEMORY_CHARS);
        String admissionContext = workerOperationalContext(detail)
                + (memoryContext.isBlank() ? "" : "\n\nDURABLE WORKER MEMORY\n" + memoryContext);
        Optional<WorkerInstructionAdmissionService.Admission> admission = instructionAdmission.evaluate(
                interaction, detail.workerId(), admissionContext);
        if (admission.isEmpty()) return Optional.empty();
        WorkerInstructionAdmissionService.Admission result = admission.get();
        PersistentWorkerConversationMemoryStore.Turn turn = memory.append(
                interaction.human().actorId(),
                detail.workerId(),
                interaction.channelProvider(),
                interaction.text(),
                result.text(),
                result.requestReference(),
                detail.runtimeId(),
                result.evidenceReferences());
        return Optional.of(new ConversationReply(detail.workerId(), detail, turn));
    }

    public List<PersistentWorkerConversationMemoryStore.Turn> history(String humanId, String requestedWorkerId) {
        WorkplaceControlRoomService.WorkerDetail detail =
                controlRoom.prepareConversationWorker(requestedWorkerId);
        return memory.history(humanId, detail.workerId());
    }

    public Optional<String> activeWorkerId(String conversationId) {
        return bindings.workerId(conversationId);
    }

    private WorkplaceControlRoomService.WorkerDetail resolveAndPrepare(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) throw new IllegalArgumentException("Worker selector required");

        String folded = fold(query);
        if (folded.equals("gateway") || folded.equals("gateway head") || folded.equals("gateway director")) {
            query = "Head of Gateway";
        }

        String workerId;
        try {
            workerId = directory.resolveActiveById(query).workerId();
        } catch (IllegalStateException byIdFailure) {
            workerId = directory.resolveActive(query).workerId();
        }
        return controlRoom.prepareConversationWorker(workerId);
    }

    private String renderDirectory() {
        List<WorkplaceControlRoomService.WorkerSummary> workers = controlRoom.snapshot().workers();
        if (workers.isEmpty()) return "👥 WORKERS\nNo ACTIVE Workers are currently available.";
        StringBuilder out = new StringBuilder("👥 WORKERS\n\n");
        workers.forEach(worker -> out.append("• ")
                .append(worker.role().isBlank() ? worker.workerId() : worker.role())
                .append("\n  ").append(worker.workerId())
                .append(" · ").append(worker.runtimeState())
                .append("\n"));
        out.append("\nTalk to one with /worker <worker id> or talk to <role>. "
                + "Once selected, keep chatting naturally; /metatron exits.");
        return out.toString();
    }

    private static boolean isDirectory(String text) {
        String q = fold(text);
        return WORKERS_CONTROL.equals(text.trim())
                || q.equals("workers")
                || q.equals("list workers")
                || q.equals("show workers")
                || q.equals("danh sach worker")
                || q.equals("cac worker")
                || q.equals("worker");
    }

    private static Optional<String> selectionQuery(String text) {
        String trimmed = text == null ? "" : text.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/worker ")) return Optional.of(trimmed.substring(8).trim());

        String folded = fold(trimmed);
        for (String prefix : List.of(
                "talk to ", "chat with ", "speak with ",
                "noi chuyen voi ", "chat voi ", "noi voi ")) {
            if (folded.startsWith(prefix) && folded.length() > prefix.length()) {
                return Optional.of(folded.substring(prefix.length()).trim());
            }
        }
        return Optional.empty();
    }

    private static boolean isExit(String text) {
        String q = fold(text);
        return q.equals("metatron")
                || q.equals("back to metatron")
                || q.equals("talk to metatron")
                || q.equals("thoat worker")
                || q.equals("roi worker")
                || "/metatron".equalsIgnoreCase(text.trim())
                || "/worker off".equalsIgnoreCase(text.trim());
    }

    private static boolean isExplicitExitCommand(String text) {
        String trimmed = text == null ? "" : text.trim();
        return "/metatron".equalsIgnoreCase(trimmed)
                || "/worker off".equalsIgnoreCase(trimmed);
    }

    private static boolean isSurfaceControl(String text) {
        String trimmed = text == null ? "" : text.trim();
        return ConversationSurfaceModeService.CHAT_CONTROL.equals(trimmed)
                || ConversationSurfaceModeService.WORK_CONTROL.equals(trimmed)
                || "/chat".equalsIgnoreCase(trimmed)
                || "/work".equalsIgnoreCase(trimmed);
    }

    private static String humanRole(WorkplaceControlRoomService.WorkerDetail detail) {
        if (detail.primaryRole() == null || detail.primaryRole().isBlank()) return "Institutional Worker";
        String value = detail.primaryRole();
        if (fold(value).contains("head of gateway")) return "Head of Gateway";
        int index = Math.max(value.lastIndexOf(':'), value.lastIndexOf('/'));
        if (index >= 0 && index + 1 < value.length()) value = value.substring(index + 1);
        return value.replace('-', ' ');
    }

    private static String workerOperationalContext(WorkplaceControlRoomService.WorkerDetail detail) {
        StringBuilder out = new StringBuilder("CURRENT CANONICAL WORKPLACE PROJECTION\n");
        out.append("worker_id=").append(detail.workerId()).append('\n')
                .append("worker_status=").append(detail.status()).append('\n')
                .append("role=").append(detail.primaryRole()).append('\n')
                .append("runtime_id=").append(detail.runtimeId()).append('\n')
                .append("runtime_state=").append(detail.runtimeState()).append('\n')
                .append("runtime_profile=").append(detail.runtimeProfile()).append('\n');
        if (detail.availability() != null) {
            out.append("available=").append(detail.availability().available()).append('\n')
                    .append("capacity=").append(detail.availability().capacity()).append('\n');
        }
        out.append("OBJECTIVES\n");
        detail.objectives().stream().limit(12).forEach(objective ->
                out.append("- ").append(objective.objectiveId())
                        .append(" | ").append(objective.executionState())
                        .append(" | ").append(objective.completedWork()).append('/').append(objective.totalWork())
                        .append(" | ").append(objective.summary()).append('\n'));
        out.append("TASKS\n");
        detail.tasks().stream().limit(24).forEach(task ->
                out.append("- ").append(task.objectiveId()).append('/').append(task.stepId())
                        .append(" | ").append(task.state())
                        .append(" | performer=").append(task.performer())
                        .append(" | actions=").append(task.actionCount())
                        .append(" | ").append(task.task()).append('\n'));
        out.append("RECENT ACTIONS\n");
        detail.actions().stream().limit(20).forEach(action ->
                out.append("- ").append(action.recordedAt())
                        .append(" | ").append(action.actionRef())
                        .append(" | success=").append(action.success())
                        .append(" | consequence=").append(action.consequence())
                        .append(" | ").append(action.summary()).append('\n'));
        return out.toString();
    }

    private static String requireToken(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        String normalized = value.trim();
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    static String fold(String value) {
        String source = value == null ? "" : value;
        String decomposed = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record HandledReply(String text, String provenanceReference, String workerId) {}
    public record ConversationReply(
            String workerId,
            WorkplaceControlRoomService.WorkerDetail worker,
            PersistentWorkerConversationMemoryStore.Turn turn) {}
}
