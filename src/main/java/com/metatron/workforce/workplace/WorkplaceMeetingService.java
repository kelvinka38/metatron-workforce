package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** First-class Workplace Meeting Room. It coordinates deliberation and durable outputs, never authority. */
@Service
public final class WorkplaceMeetingService {
    private static final Pattern FOLLOW_UP_REFERENCE = Pattern.compile("meeting-follow-up:(meeting:[a-zA-Z0-9._:-]+)");
    private static final Pattern WORKER_REFERENCE = Pattern.compile("\\b((?:worker:[a-zA-Z0-9._:-]+|WORKER-[a-zA-Z0-9._:-]+))\\b", Pattern.CASE_INSENSITIVE);
    private final PersistentMeetingStore store;
    private final ExecutionObjectiveHandoff executionObjectiveHandoff;
    private final MeetingWorkerDirectory workerDirectory;
    private final WorkerConversationGateway workerConversation;

    @Autowired
    public WorkplaceMeetingService(
            ExecutionObjectiveHandoff executionObjectiveHandoff,
            MeetingWorkerDirectory workerDirectory,
            WorkerConversationGateway workerConversation,
            @Value("${METATRON_WORKPLACE_MEETING_PATH:/var/lib/metatron-workforce/workplace/meetings}") String meetingPath,
            ObjectMapper json) {
        this(new PersistentMeetingStore(Path.of(meetingPath), json),
                Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff"),
                Objects.requireNonNull(workerDirectory, "workerDirectory"),
                Objects.requireNonNull(workerConversation, "workerConversation"));
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator) {
        this(store, ExecutionObjectiveHandoff.unavailable(), null, legacyWorkerConversation(deliberator));
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator,
                            ExecutionObjectiveHandoff executionObjectiveHandoff) {
        this(store, executionObjectiveHandoff, null, legacyWorkerConversation(deliberator));
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator,
                            ExecutionObjectiveHandoff executionObjectiveHandoff,
                            MeetingWorkerDirectory workerDirectory) {
        this(store, executionObjectiveHandoff, workerDirectory, legacyWorkerConversation(deliberator));
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator,
                            ExecutionObjectiveHandoff executionObjectiveHandoff,
                            MeetingWorkerDirectory workerDirectory,
                            WorkerConversationGateway workerConversation) {
        Objects.requireNonNull(deliberator, "deliberator");
        this.store = Objects.requireNonNull(store, "store");
        this.executionObjectiveHandoff = Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff");
        this.workerDirectory = workerDirectory;
        this.workerConversation = Objects.requireNonNull(workerConversation, "workerConversation");
    }

    private WorkplaceMeetingService(PersistentMeetingStore store,
                                    ExecutionObjectiveHandoff executionObjectiveHandoff,
                                    MeetingWorkerDirectory workerDirectory,
                                    WorkerConversationGateway workerConversation) {
        this.store = Objects.requireNonNull(store, "store");
        this.executionObjectiveHandoff = Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff");
        this.workerDirectory = workerDirectory;
        this.workerConversation = Objects.requireNonNull(workerConversation, "workerConversation");
    }

    private static WorkerConversationGateway legacyWorkerConversation(MeetingRoleDeliberator deliberator) {
        Objects.requireNonNull(deliberator, "deliberator");
        return (workerId, role, userMessage, context) -> {
            MeetingRoleDeliberator.Deliberation reply = deliberator.converse(workerId, role, userMessage, context);
            List<String> evidence = reply.providerReference().isBlank()
                    ? List.of()
                    : List.of(reply.providerReference());
            return new WorkerConversationGateway.Reply(reply.text(), reply.providerReference(), evidence);
        };
    }

    /** Meeting-mode route: the Human organizer is an implicit participant, so one requested institutional role is enough. */
    public boolean supportsInMeetingMode(String text) {
        if (text == null || text.isBlank()) return false;
        return isAuthorizedFollowUp(text) || isWorkerDirectoryRequest(text) || !requestedWorkerIds(text).isEmpty() || requestedRoles(text).size() >= 1;
    }

    /** Conservative first-class route from AUTO/Chat semantics: marker plus roles, or explicit follow-up. */
    public boolean supports(String text) {
        if (text == null || text.isBlank()) return false;
        if (isAuthorizedFollowUp(text)) return true;
        if (!requestedWorkerIds(text).isEmpty()) return true;
        String lower = normalize(text);
        boolean meetingMarker = lower.contains("meeting") || lower.contains("meeting room")
                || lower.contains("hop ") || lower.startsWith("hop")
                || lower.contains("vao ban") || lower.contains("ban ve")
                || lower.contains("trieu tap") || lower.contains("moi ")
                || lower.contains("goi ") || lower.contains("summon")
                || lower.contains("bring ") && lower.contains(" into ");
        return meetingMarker && requestedRoles(text).size() >= 1;
    }

    public String handle(MetatronInteraction interaction, String conversationContext) {
        Objects.requireNonNull(interaction, "interaction");
        if (isAuthorizedFollowUp(interaction.text())) return handoffFollowUp(interaction);
        if (isWorkerDirectoryRequest(interaction.text())) return renderActiveWorkers();

        java.util.Optional<MeetingRecord> active = findActiveConversationMeeting(interaction.conversationId());
        List<String> directWorkerIds = requestedWorkerIds(interaction.text());
        if (!directWorkerIds.isEmpty()) {
            if (workerDirectory == null) throw new IllegalStateException("meeting_worker_directory_unavailable");
            List<MeetingWorkerDirectory.ResolvedWorker> resolved = new ArrayList<>();
            List<String> unavailable = new ArrayList<>();
            for (String workerId : directWorkerIds) {
                try {
                    MeetingWorkerDirectory.ResolvedWorker worker = workerDirectory.resolveActiveById(workerId);
                    if (resolved.stream().noneMatch(existing -> existing.workerId().equals(worker.workerId()))) {
                        resolved.add(worker);
                    }
                } catch (RuntimeException failure) {
                    unavailable.add(workerId + " — " + safeFailure(failure));
                }
            }
            if (!unavailable.isEmpty()) return renderWorkerUnavailable(unavailable);
            if (active.isPresent()) {
                return continueConversation(active.get(), interaction, conversationContext, List.copyOf(resolved));
            }
            return openConversation(interaction, List.copyOf(resolved));
        }

        List<String> roles = requestedRoles(interaction.text());
        if (active.isPresent()) {
            if (roles.isEmpty()) {
                return continueConversation(active.get(), interaction, conversationContext, List.of());
            }
            RoleResolution requested = resolveRoles(roles);
            if (!requested.unavailable().isEmpty()) return renderWorkerUnavailable(requested.unavailable());
            return continueConversation(active.get(), interaction, conversationContext, requested.workers());
        }

        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "Meeting Room requires at least one ACTIVE Worker role or explicit worker ID; the Human organizer is the other participant");
        }

        RoleResolution requested = resolveRoles(roles);
        if (!requested.unavailable().isEmpty()) return renderWorkerUnavailable(requested.unavailable());
        return openConversation(interaction, requested.workers());
    }


    public boolean hasActiveConversationMeeting(String conversationId) {
        return findActiveConversationMeeting(conversationId).isPresent();
    }

    /** Leaving Meeting closes any active room so stale sessions cannot hijack later Work turns. */
    public void closeActiveConversation(String conversationId) {
        closePriorConversationMeetings(conversationId);
    }

    private java.util.Optional<MeetingRecord> findActiveConversationMeeting(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return java.util.Optional.empty();
        return store.list().stream()
                .filter(m -> conversationId.equals(m.conversationId()))
                .filter(m -> m.status() == MeetingRecord.Status.ACTIVE)
                .filter(m -> m.participants().size() >= 2)
                .sorted(java.util.Comparator.comparing(MeetingRecord::openedAt).reversed()
                        .thenComparing(java.util.Comparator.comparing(MeetingRecord::meetingId).reversed()))
                .findFirst();
    }

    private RoleResolution resolveRoles(List<String> roles) {
        List<MeetingWorkerDirectory.ResolvedWorker> workers = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String role : roles) {
            try {
                MeetingWorkerDirectory.ResolvedWorker worker = requireBoundWorker(role);
                if (workers.stream().noneMatch(existing -> existing.workerId().equals(worker.workerId()))) {
                    workers.add(worker);
                }
            } catch (RuntimeException failure) {
                unavailable.add(role + " — " + safeFailure(failure));
            }
        }
        return new RoleResolution(List.copyOf(workers), List.copyOf(unavailable));
    }

    private String openConversation(MetatronInteraction interaction,
                                    List<MeetingWorkerDirectory.ResolvedWorker> requestedWorkers) {
        if (requestedWorkers == null || requestedWorkers.isEmpty()) {
            throw new IllegalArgumentException("Meeting requires at least one real ACTIVE Worker");
        }
        closePriorConversationMeetings(interaction.conversationId());

        List<MeetingWorkerDirectory.ResolvedWorker> workers = requestedWorkers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MeetingWorkerDirectory.ResolvedWorker::workerId,
                        worker -> worker,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();

        String id = "meeting:" + UUID.randomUUID().toString().replace("-", "");
        String now = Instant.now().toString();
        String organizer = "human:" + interaction.human().actorId();
        List<String> participants = new ArrayList<>();
        participants.add(organizer);
        workers.forEach(worker -> participants.add(worker.workerId()));

        List<String> lifecycle = List.of(
                MeetingRecord.Status.PROPOSED.name(),
                MeetingRecord.Status.OPEN.name(),
                MeetingRecord.Status.ACTIVE.name());
        List<MeetingRecord.Contribution> contributions = new ArrayList<>();
        List<String> evidence = new ArrayList<>(baseEvidence(interaction));
        List<WorkerReply> replies = new ArrayList<>();

        for (MeetingWorkerDirectory.ResolvedWorker worker : workers) {
            WorkerConversationGateway.Reply reply = workerConversation.converse(
                    worker.workerId(), worker.role(), interaction.text(), "");
            contributions.add(new MeetingRecord.Contribution(
                    worker.workerId(), worker.role(), reply.text(), reply.requestReference()));
            evidence.add("meeting-worker:" + worker.workerId() + ":participation=" + worker.participationId());
            reply.evidenceReferences().stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .filter(ref -> !evidence.contains(ref))
                    .forEach(evidence::add);
            replies.add(new WorkerReply(worker, reply.text(), reply.runtimeId()));
        }

        MeetingRecord meeting = new MeetingRecord(
                id, interaction.organizationContextId(), interaction.conversationId(),
                interaction.channelProvider(), interaction.externalMessageReference(),
                workers.size() == 1 ? "Conversation with " + workers.getFirst().role() : "Live Worker Meeting",
                interaction.text(), organizer, List.copyOf(participants),
                List.of("Live conversation"), List.copyOf(contributions), "", List.of(), List.of(),
                List.copyOf(evidence), lifecycle, MeetingRecord.Status.ACTIVE, now, "", false);
        store.save(meeting);
        return renderWorkerReplies(replies);
    }

    private String continueConversation(MeetingRecord meeting,
                                        MetatronInteraction interaction,
                                        String conversationContext,
                                        List<MeetingWorkerDirectory.ResolvedWorker> explicitlyRequestedWorkers) {
        String expectedOrganizer = "human:" + interaction.human().actorId();
        if (!expectedOrganizer.equals(meeting.organizer())) throw new SecurityException("meeting organizer mismatch");
        if (!interaction.organizationContextId().equals(meeting.organizationContextId())) {
            throw new SecurityException("meeting organization mismatch");
        }
        if (workerDirectory == null) {
            throw new IllegalStateException("meeting_worker_directory_unavailable");
        }

        java.util.LinkedHashMap<String, MeetingWorkerDirectory.ResolvedWorker> roomWorkers =
                new java.util.LinkedHashMap<>();
        for (int i = 1; i < meeting.participants().size(); i++) {
            String participant = meeting.participants().get(i);
            try {
                MeetingWorkerDirectory.ResolvedWorker current;
                if (participant.startsWith("role:")) {
                    current = workerDirectory.resolveActive(latestRoleForParticipant(meeting, participant));
                } else {
                    current = workerDirectory.resolveActiveById(participant);
                }
                roomWorkers.put(current.workerId(), current);
            } catch (RuntimeException unavailable) {
                return renderWorkerUnavailable(List.of(participant + " — " + safeFailure(unavailable)));
            }
        }

        List<MeetingWorkerDirectory.ResolvedWorker> requested =
                explicitlyRequestedWorkers == null ? List.of() : explicitlyRequestedWorkers;
        for (MeetingWorkerDirectory.ResolvedWorker worker : requested) {
            roomWorkers.put(worker.workerId(), worker);
        }

        List<MeetingWorkerDirectory.ResolvedWorker> targets = requested.isEmpty()
                ? List.copyOf(roomWorkers.values())
                : requested.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                MeetingWorkerDirectory.ResolvedWorker::workerId,
                                worker -> worker,
                                (first, ignored) -> first,
                                java.util.LinkedHashMap::new))
                        .values().stream().toList();
        if (targets.isEmpty()) throw new IllegalStateException("meeting_has_no_live_workers");

        List<String> participants = new ArrayList<>();
        participants.add(meeting.organizer());
        roomWorkers.values().forEach(worker -> participants.add(worker.workerId()));

        List<MeetingRecord.Contribution> contributions = new ArrayList<>(meeting.contributions());
        List<String> evidence = new ArrayList<>(meeting.evidenceRefs());
        evidence.add("interaction:" + interaction.externalMessageReference());
        List<WorkerReply> replies = new ArrayList<>();

        String roomContext = conversationContext
                + "\n\nLIVE MEETING WORKER IDS="
                + roomWorkers.keySet();
        for (MeetingWorkerDirectory.ResolvedWorker worker : targets) {
            WorkerConversationGateway.Reply reply = workerConversation.converse(
                    worker.workerId(), worker.role(), interaction.text(), roomContext);
            contributions.add(new MeetingRecord.Contribution(
                    worker.workerId(), worker.role(), reply.text(), reply.requestReference()));
            String bindingEvidence = "meeting-worker:" + worker.workerId()
                    + ":participation=" + worker.participationId();
            if (!evidence.contains(bindingEvidence)) evidence.add(bindingEvidence);
            reply.evidenceReferences().stream()
                    .filter(ref -> ref != null && !ref.isBlank())
                    .filter(ref -> !evidence.contains(ref))
                    .forEach(evidence::add);
            replies.add(new WorkerReply(worker, reply.text(), reply.runtimeId()));
        }

        MeetingRecord updated = new MeetingRecord(
                meeting.meetingId(), meeting.organizationContextId(), meeting.conversationId(),
                meeting.channelProvider(), meeting.externalMessageReference(),
                roomWorkers.size() == 1 ? "Conversation with " + roomWorkers.values().iterator().next().role()
                        : "Live Worker Meeting",
                meeting.purpose(), meeting.organizer(), List.copyOf(participants),
                meeting.agenda(), List.copyOf(contributions), meeting.recommendation(),
                meeting.actionItems(), meeting.decisionRefs(), List.copyOf(evidence),
                meeting.lifecycle(), MeetingRecord.Status.ACTIVE, meeting.openedAt(), "", false);
        store.save(updated);
        return renderWorkerReplies(replies);
    }

    private void closePriorConversationMeetings(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        String closedAt = Instant.now().toString();
        for (MeetingRecord prior : store.list()) {
            if (!conversationId.equals(prior.conversationId())
                    || prior.status() != MeetingRecord.Status.ACTIVE
                    || prior.participants().size() < 2) {
                continue;
            }
            List<String> lifecycle = new ArrayList<>(prior.lifecycle());
            if (!lifecycle.contains(MeetingRecord.Status.CLOSED.name())) {
                lifecycle.add(MeetingRecord.Status.CLOSED.name());
            }
            MeetingRecord closed = new MeetingRecord(
                    prior.meetingId(), prior.organizationContextId(), prior.conversationId(),
                    prior.channelProvider(), prior.externalMessageReference(), prior.title(), prior.purpose(),
                    prior.organizer(), prior.participants(), prior.agenda(), prior.contributions(),
                    prior.recommendation(), prior.actionItems(), prior.decisionRefs(), prior.evidenceRefs(),
                    lifecycle, MeetingRecord.Status.CLOSED, prior.openedAt(), closedAt, false);
            store.save(closed);
        }
    }

    private MeetingWorkerDirectory.ResolvedWorker requireBoundWorker(String role) {
        if (workerDirectory == null) {
            return new MeetingWorkerDirectory.ResolvedWorker("role:" + slug(role), "test-unbound", role, "", "");
        }
        return workerDirectory.resolveActive(role);
    }

    private static String latestRoleForParticipant(MeetingRecord meeting, String participant) {
        for (int i = meeting.contributions().size() - 1; i >= 0; i--) {
            MeetingRecord.Contribution contribution = meeting.contributions().get(i);
            if (participant.equals(contribution.participant()) && !contribution.role().isBlank()) {
                return contribution.role();
            }
        }
        String value = participant == null ? "" : participant;
        if (value.startsWith("role:")) {
            return java.util.Arrays.stream(value.substring(5).split("-"))
                    .filter(token -> !token.isBlank())
                    .map(token -> Character.toUpperCase(token.charAt(0)) + token.substring(1))
                    .collect(java.util.stream.Collectors.joining(" "));
        }
        return "Institutional Worker";
    }

    private static String renderWorkerReplies(List<WorkerReply> replies) {
        StringBuilder out = new StringBuilder();
        for (WorkerReply reply : replies) {
            if (!out.isEmpty()) out.append("\n\n");
            out.append("🏛 **").append(reply.worker().role()).append("**\n")
                    .append("worker_id=`").append(reply.worker().workerId()).append("`\n")
                    .append("runtime_id=`").append(reply.runtimeId().isBlank() ? "UNAVAILABLE" : reply.runtimeId()).append("`\n")
                    .append("runtime_state=").append(reply.runtimeId().isBlank() ? "UNVERIFIED" : "RUNNING")
                    .append("\n\n")
                    .append(reply.text());
        }
        return out.toString();
    }
    private static String renderWorkerUnavailable(List<String> unavailable) {
        StringBuilder out = new StringBuilder("🏛 **WORKER NOT AVAILABLE**\n\n");
        for (String item : unavailable) out.append("- ").append(item).append('\n');
        out.append("\nNo role/persona simulation was used. Create or activate a real Worker first, then call its Worker ID.");
        return out.toString().trim();
    }

    private static String safeFailure(RuntimeException failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return "worker resolution failed";
        }
        return failure.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
    }

    private record RoleResolution(List<MeetingWorkerDirectory.ResolvedWorker> workers,
                                  List<String> unavailable) {}
    private record WorkerReply(MeetingWorkerDirectory.ResolvedWorker worker, String text, String runtimeId) {}

    private String handoffFollowUp(MetatronInteraction interaction) {
        Matcher matcher = FOLLOW_UP_REFERENCE.matcher(interaction.text());
        if (!matcher.find()) throw new IllegalArgumentException("Meeting follow-up reference required");
        String meetingId = matcher.group(1);
        MeetingRecord meeting = require(meetingId);
        String expectedOrganizer = "human:" + interaction.human().actorId();
        if (!expectedOrganizer.equals(meeting.organizer())) {
            throw new SecurityException("meeting follow-up organizer mismatch");
        }
        if (!interaction.organizationContextId().equals(meeting.organizationContextId())) {
            throw new SecurityException("meeting follow-up organization mismatch");
        }

        String canonicalControl = "Take ownership of one governed meeting-derived objective: "
                + "Meeting purpose: " + meeting.purpose()
                + ". Meeting recommendation: " + meeting.recommendation()
                + ". Source meeting " + meeting.meetingId()
                + ". Preserve evidence " + meeting.followUpReference()
                + ". Do not treat the Meeting itself as execution authority.";
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(canonicalControl)
                .orElseThrow(() -> new IllegalStateException("meeting follow-up normalization failed"));

        ExecutionObjectiveHandoff.HandoffReceipt handoff = executionObjectiveHandoff.submit(
                interaction.human().actorId(),
                interaction.organizationContextId(),
                "meeting-case:" + meeting.meetingId(),
                interaction.conversationId(),
                interaction.externalMessageReference(),
                interaction.channelProvider(),
                request);
        if (!handoff.accepted()) {
            return "METATRON MEETING WORK BLOCKED"
                    + "\nmeeting_id=" + meeting.meetingId()
                    + "\nfollow_up_ref=" + meeting.followUpReference()
                    + "\nreason=" + handoff.reason();
        }

        List<String> updatedActions = new ArrayList<>(meeting.actionItems());
        updatedActions.add("objective_id=" + handoff.objectiveId()
                + "; source=" + meeting.followUpReference()
                + "; authorized_by=" + expectedOrganizer);
        List<String> updatedEvidence = new ArrayList<>(meeting.evidenceRefs());
        updatedEvidence.add("meeting-work-handoff:" + meeting.followUpReference()
                + ":objective=" + handoff.objectiveId()
                + ":human-authorized=true:meeting-authority-created=false");
        List<String> updatedDecisionRefs = new ArrayList<>(meeting.decisionRefs());
        updatedDecisionRefs.add(handoff.objectiveId());

        MeetingRecord updated = new MeetingRecord(
                meeting.meetingId(), meeting.organizationContextId(), meeting.conversationId(),
                meeting.channelProvider(), meeting.externalMessageReference(), meeting.title(), meeting.purpose(),
                meeting.organizer(), meeting.participants(), meeting.agenda(), meeting.contributions(),
                meeting.recommendation(), updatedActions, updatedDecisionRefs, updatedEvidence,
                meeting.lifecycle(), MeetingRecord.Status.FOLLOW_UP, meeting.openedAt(), meeting.closedAt(), false);
        store.save(updated);

        return "METATRON MEETING WORK ACCEPTED"
                + "\nmeeting_id=" + meeting.meetingId()
                + "\nfollow_up_ref=" + meeting.followUpReference()
                + "\nobjective_id=" + handoff.objectiveId()
                + "\nowner_worker=" + handoff.ownerWorkerId()
                + "\nqueue_item=" + handoff.queueItemId()
                + "\nobjective_status=" + handoff.objectiveStatus()
                + "\nexecution_state=" + handoff.executionAdmissionState()
                + "\nauthority_source=explicit-human-meeting-follow-up";
    }

    public static boolean isAuthorizedFollowUp(String text) {
        if (text == null || text.isBlank()) return false;
        Matcher matcher = FOLLOW_UP_REFERENCE.matcher(text);
        if (!matcher.find()) return false;
        String lower = normalize(text);
        return lower.contains("execute") || lower.contains("implement")
                || lower.contains("proceed") || lower.contains("approve")
                || lower.contains("giao workforce") || lower.contains("cho workforce")
                || lower.contains("thuc hien") || lower.contains("trien khai")
                || lower.contains("lam di") || lower.contains("tiep tuc");
    }

    private String renderActiveWorkers() {
        if (workerDirectory == null) throw new IllegalStateException("meeting_worker_directory_unavailable");
        List<MeetingWorkerDirectory.ResolvedWorker> workers = workerDirectory.listActive();
        if (workers.isEmpty()) {
            return "🏛 **LIVE WORKERS**\n\nNo Worker currently has a RUNNING runtime instance.";
        }
        StringBuilder out = new StringBuilder("🏛 **LIVE WORKERS**\n\n");
        for (MeetingWorkerDirectory.ResolvedWorker worker : workers) {
            out.append("worker_id=`").append(worker.workerId()).append("`\n")
                    .append("runtime_id=`").append(worker.runtimeId()).append("`\n")
                    .append("runtime_state=").append(worker.runtimeState()).append("\n")
                    .append("role=").append(worker.role()).append("\n")
                    .append("role_ref=").append(worker.roleRef()).append("\n")
                    .append("participation_id=`").append(worker.participationId()).append("`\n\n");
        }
        out.append("Call a Worker by its exact `worker_id`; Meeting will bind to the RUNNING `runtime_id` shown above.");
        return out.toString();
    }
    public static boolean isWorkerDirectoryRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = normalize(text);
        return lower.equals("workers")
                || lower.equals("active workers")
                || lower.contains("list active workers")
                || lower.contains("show active workers")
                || lower.contains("worker ids")
                || lower.contains("worker id")
                || lower.contains("danh sach worker")
                || lower.contains("cac worker")
                || lower.contains("worker dang hoat dong");
    }

    public List<MeetingRecord> list() { return store.list(); }
    public MeetingRecord require(String meetingId) {
        return store.find(meetingId).orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
    }
    public java.util.Optional<MeetingRecord> findByExternalMessageReference(String ref) {
        return store.findByExternalMessageReference(ref);
    }

    static java.util.Optional<String> requestedWorkerId(String text) {
        List<String> workers = requestedWorkerIds(text);
        return workers.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(workers.getFirst());
    }

    static List<String> requestedWorkerIds(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher matcher = WORKER_REFERENCE.matcher(text);
        LinkedHashSet<String> workers = new LinkedHashSet<>();
        while (matcher.find()) workers.add(matcher.group(1));
        return List.copyOf(workers);
    }

    static List<String> requestedRoles(String text) {
        if (text == null) return List.of();
        String lower = normalize(text);

        // Full institutional titles are always explicit. Domain words such as "finance" or
        // "operations" are not automatically participants merely because the Human discusses them.
        Set<String> roles = new LinkedHashSet<>();
        addRole(lower, roles, "Head of Gateway",
                "head of gateway", "gateway head", "gateway director", "director of gateway");
        addRole(lower, roles, "Head of Strategy",
                "head of strategy", "strategy head", "director of strategy");
        addRole(lower, roles, "Head of Finance",
                "head of finance", "finance head", "finance director", "director of finance", "cfo");
        addRole(lower, roles, "Head of Operations",
                "head of operations", "operations head", "operations director", "director of operations",
                "head of ops", "ops head", "director of ops", "coo");
        addRole(lower, roles, "Head of Technology",
                "head of technology", "technology head", "technology director", "director of technology",
                "head of tech", "tech head", "director of tech", "cto");
        addRole(lower, roles, "Head of Sales",
                "head of sales", "sales head", "sales director", "director of sales");
        addRole(lower, roles, "Head of Product",
                "head of product", "product head", "product director", "director of product");
        addRole(lower, roles, "Head of People",
                "head of people", "people head", "people director", "director of people",
                "head of human resources", "hr director");

        // Dynamic explicit institutional titles remain supported.
        java.util.regex.Matcher dynamic = java.util.regex.Pattern
                .compile("\\b(?:head|director) of ([a-z0-9][a-z0-9 &/-]{1,36}?)(?=,|\\band\\b|\\bdiscuss\\b|\\bcreate\\b|\\babout\\b|\\bfor\\b|$)")
                .matcher(lower);
        while (dynamic.find()) {
            String domain = dynamic.group(1).trim().replaceAll("\\s+", " ");
            if (domain.isBlank()) continue;
            String canonical = switch (domain) {
                case "gateway" -> "Head of Gateway";
                case "strategy" -> "Head of Strategy";
                case "finance", "financial" -> "Head of Finance";
                case "operations", "operation", "ops" -> "Head of Operations";
                case "technology", "technical", "tech" -> "Head of Technology";
                case "sales", "commercial" -> "Head of Sales";
                case "product" -> "Head of Product";
                case "people", "human resources", "hr" -> "Head of People";
                default -> "";
            };
            String role = canonical.isBlank()
                    ? "Head of " + java.util.Arrays.stream(domain.split(" "))
                            .filter(token -> !token.isBlank())
                            .map(token -> Character.toUpperCase(token.charAt(0)) + token.substring(1))
                            .collect(java.util.stream.Collectors.joining(" "))
                    : canonical;
            roles.add(role);
        }

        // Bare department names are promoted to participants only inside the invitation/roster span,
        // never from the discussion topic. Example:
        // "Meeting with Head of Gateway about finance strategy" => Gateway only.
        // "Mời Strategy, Finance và Operations họp về P&L" => three requested Workers.
        String roster = invitationRoster(lower);
        if (!roster.isBlank()) {
            addRole(roster, roles, "Head of Strategy", "strategy", "chien luoc");
            addRole(roster, roles, "Head of Finance", "finance", "financial", "tai chinh");
            addRole(roster, roles, "Head of Operations", "operations", "operation", "ops", "van hanh");
            addRole(roster, roles, "Head of Technology", "technology", "technical", "tech", "ky thuat");
            addRole(roster, roles, "Head of Sales", "sales", "commercial", "kinh doanh", "doanh thu");
            addRole(roster, roles, "Head of Product", "product", "san pham");
            addRole(roster, roles, "Head of People", "people", "human resources", "hr", "nhan su");
        }
        return List.copyOf(roles);
    }

    private static String invitationRoster(String lower) {
        if (lower == null || lower.isBlank()) return "";
        String[] starts = {
                "moi ", "goi ", "summon ", "bring ", "call ",
                "meeting with ", "meet with ", "hop voi ", "hop ", "meeting "
        };
        int start = Integer.MAX_VALUE;
        for (String marker : starts) {
            int at = lower.indexOf(marker);
            if (at >= 0 && at < start) start = at;
        }
        if (start == Integer.MAX_VALUE) return "";

        String[] ends = {
                " about ", " discuss ", " to discuss ", " regarding ",
                " vao ban ", " ban ve ", " hop ve ", " de ban "
        };
        int end = lower.length();
        for (String marker : ends) {
            int at = lower.indexOf(marker, start + 1);
            if (at >= 0 && at < end) end = at;
        }
        return lower.substring(start, end);
    }

    private static void addRole(String lower, Set<String> roles, String role, String... tokens) {
        for (String token : tokens) if (containsToken(lower, token)) { roles.add(role); return; }
    }
    private static boolean containsToken(String value, String token) {
        if (token.contains(" ")) return value.contains(token);
        return (" " + value + " ").matches(".*[^a-z0-9]" + java.util.regex.Pattern.quote(token) + "[^a-z0-9].*");
    }
    private static String normalize(String value) {
        String n = java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd');
    }
    private static String slug(String role) {
        return role.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
    private static List<String> baseEvidence(MetatronInteraction i) {
        return List.of("conversation:" + i.conversationId(), "interaction:" + i.externalMessageReference(),
                "channel:" + i.channelProvider());
    }


}
