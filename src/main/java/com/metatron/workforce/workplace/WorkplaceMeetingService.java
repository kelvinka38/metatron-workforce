package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.InstitutionalIntelligenceRuntime;
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
    private final PersistentMeetingStore store;
    private final MeetingRoleDeliberator deliberator;
    private final ExecutionObjectiveHandoff executionObjectiveHandoff;

    @Autowired
    public WorkplaceMeetingService(
            InstitutionalIntelligenceRuntime intelligenceRuntime,
            ExecutionObjectiveHandoff executionObjectiveHandoff,
            @Value("${METATRON_WORKPLACE_MEETING_PATH:/var/lib/metatron-workforce/workplace/meetings}") String meetingPath,
            ObjectMapper json) {
        this(new PersistentMeetingStore(Path.of(meetingPath), json),
                MeetingRoleDeliberator.intelligenceBacked(
                        Objects.requireNonNull(intelligenceRuntime, "intelligenceRuntime").fabric(),
                        intelligenceRuntime.configuredProviders().size()),
                Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff"));
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator) {
        this(store, deliberator, ExecutionObjectiveHandoff.unavailable());
    }

    WorkplaceMeetingService(PersistentMeetingStore store, MeetingRoleDeliberator deliberator,
                            ExecutionObjectiveHandoff executionObjectiveHandoff) {
        this.store = Objects.requireNonNull(store, "store");
        this.deliberator = Objects.requireNonNull(deliberator, "deliberator");
        this.executionObjectiveHandoff = Objects.requireNonNull(executionObjectiveHandoff, "executionObjectiveHandoff");
    }

    /** Conservative first-class route: create a Meeting or explicitly authorize a durable Meeting follow-up. */
    public boolean supports(String text) {
        if (text == null || text.isBlank()) return false;
        if (isAuthorizedFollowUp(text)) return true;
        String lower = normalize(text);
        boolean meetingMarker = lower.contains("meeting") || lower.contains("meeting room")
                || lower.contains("hop ") || lower.startsWith("hop")
                || lower.contains("vao ban") || lower.contains("ban ve")
                || lower.contains("trieu tap") || lower.contains("moi ")
                || lower.contains("goi ") || lower.contains("summon")
                || lower.contains("bring ") && lower.contains(" into ");
        return meetingMarker && requestedRoles(text).size() >= 2;
    }

    public String handle(MetatronInteraction interaction, String conversationContext) {
        Objects.requireNonNull(interaction, "interaction");
        if (isAuthorizedFollowUp(interaction.text())) return handoffFollowUp(interaction);
        List<String> roles = requestedRoles(interaction.text());
        if (roles.size() < 2) throw new IllegalArgumentException("Meeting Room requires at least two explicit roles");

        String id = "meeting:" + UUID.randomUUID().toString().replace("-", "");
        String followUpRef = "meeting-follow-up:" + id;
        String now = Instant.now().toString();
        String organizer = "human:" + interaction.human().actorId();
        List<String> participants = new ArrayList<>();
        participants.add(organizer);
        participants.addAll(roles.stream().map(r -> "role:" + slug(r)).toList());
        List<String> agenda = List.of("Role assessments", "Material disagreements", "Recommendation and follow-up");
        List<String> lifecycle = new ArrayList<>();
        lifecycle.add(MeetingRecord.Status.PROPOSED.name());

        MeetingRecord current = snapshot(id, interaction, organizer, participants, agenda, List.of(), "",
                List.of(), List.of(), baseEvidence(interaction), lifecycle,
                MeetingRecord.Status.PROPOSED, now, "");
        store.save(current);

        lifecycle.add(MeetingRecord.Status.OPEN.name());
        current = copy(current, List.of(), "", List.of(), lifecycle, MeetingRecord.Status.OPEN, "");
        store.save(current);

        List<MeetingRecord.Contribution> contributions = new ArrayList<>();
        lifecycle.add(MeetingRecord.Status.ACTIVE.name());
        for (String role : roles) {
            MeetingRoleDeliberator.Deliberation result = deliberator.deliberate(role, interaction.text(), conversationContext);
            contributions.add(new MeetingRecord.Contribution("role:" + slug(role), role, result.text(), result.providerReference()));
            current = copy(current, contributions, "", List.of(), lifecycle, MeetingRecord.Status.ACTIVE, "");
            store.save(current);
        }

        lifecycle.add(MeetingRecord.Status.DECISION_PENDING.name());
        current = copy(current, contributions, "", List.of(), lifecycle, MeetingRecord.Status.DECISION_PENDING, "");
        store.save(current);

        MeetingRoleDeliberator.Deliberation synthesis = deliberator.synthesize(interaction.text(), contributions, conversationContext);
        List<String> actionItems = List.of(
                "handoff_ref=" + followUpRef
                        + "; Founder may use this durable reference for a separately authorized Objective/Decision. "
                        + "The Meeting itself creates no execution authority.");
        lifecycle.add(MeetingRecord.Status.CLOSED.name());
        String closedAt = Instant.now().toString();
        List<String> evidence = new ArrayList<>(baseEvidence(interaction));
        for (MeetingRecord.Contribution c : contributions) {
            if (!c.providerReference().isBlank()) evidence.add(c.providerReference());
        }
        if (!synthesis.providerReference().isBlank()) evidence.add(synthesis.providerReference());
        evidence.add("meeting-handoff:" + followUpRef
                + ":route=authorized-objective-or-decision:authority-created=false");
        current = new MeetingRecord(id, interaction.organizationContextId(), interaction.conversationId(),
                interaction.channelProvider(), interaction.externalMessageReference(), "Institutional multi-role meeting",
                interaction.text(), organizer, participants, agenda, contributions, synthesis.text(), actionItems,
                List.of(), evidence, lifecycle, MeetingRecord.Status.CLOSED, now, closedAt, false);
        store.save(current);

        lifecycle.add(MeetingRecord.Status.FOLLOW_UP.name());
        current = new MeetingRecord(id, interaction.organizationContextId(), interaction.conversationId(),
                interaction.channelProvider(), interaction.externalMessageReference(), current.title(), current.purpose(),
                organizer, participants, agenda, contributions, synthesis.text(), actionItems, List.of(), evidence,
                lifecycle, MeetingRecord.Status.FOLLOW_UP, now, closedAt, false);
        store.save(current);
        return render(current);
    }


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

    static boolean isAuthorizedFollowUp(String text) {
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

    public List<MeetingRecord> list() { return store.list(); }
    public MeetingRecord require(String meetingId) {
        return store.find(meetingId).orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
    }
    public java.util.Optional<MeetingRecord> findByExternalMessageReference(String ref) {
        return store.findByExternalMessageReference(ref);
    }

    static List<String> requestedRoles(String text) {
        if (text == null) return List.of();
        String lower = normalize(text);
        Set<String> roles = new LinkedHashSet<>();
        addRole(lower, roles, "Head of Strategy", "strategy", "chien luoc");
        addRole(lower, roles, "Head of Finance", "finance", "financial", "tai chinh", "cfo");
        addRole(lower, roles, "Head of Operations", "operations", "operation", "ops", "van hanh");
        addRole(lower, roles, "Head of Technology", "technology", "technical", "tech", "ky thuat", "cto");
        addRole(lower, roles, "Head of Sales", "sales", "commercial", "kinh doanh", "doanh thu");
        addRole(lower, roles, "Head of Product", "product", "san pham");
        addRole(lower, roles, "Head of People", "people", "human resources", "hr", "nhan su");
        return List.copyOf(roles);
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

    private static MeetingRecord snapshot(String id, MetatronInteraction i, String organizer,
                                          List<String> participants, List<String> agenda,
                                          List<MeetingRecord.Contribution> contributions, String recommendation,
                                          List<String> actionItems, List<String> decisionRefs, List<String> evidence,
                                          List<String> lifecycle, MeetingRecord.Status status,
                                          String openedAt, String closedAt) {
        return new MeetingRecord(id, i.organizationContextId(), i.conversationId(), i.channelProvider(),
                i.externalMessageReference(), "Institutional multi-role meeting", i.text(), organizer,
                participants, agenda, contributions, recommendation, actionItems, decisionRefs, evidence,
                lifecycle, status, openedAt, closedAt, false);
    }
    private static MeetingRecord copy(MeetingRecord prior, List<MeetingRecord.Contribution> contributions,
                                      String recommendation, List<String> actionItems, List<String> lifecycle,
                                      MeetingRecord.Status status, String closedAt) {
        return new MeetingRecord(prior.meetingId(), prior.organizationContextId(), prior.conversationId(),
                prior.channelProvider(), prior.externalMessageReference(), prior.title(), prior.purpose(), prior.organizer(),
                prior.participants(), prior.agenda(), contributions, recommendation, actionItems, prior.decisionRefs(),
                prior.evidenceRefs(), lifecycle, status, prior.openedAt(), closedAt, false);
    }

    private static String render(MeetingRecord m) {
        StringBuilder out = new StringBuilder("METATRON MEETING COMPLETED\n")
                .append("meeting_id=").append(m.meetingId()).append('\n')
                .append("status=").append(m.status()).append('\n')
                .append("participants=").append(String.join(", ", m.participants())).append('\n')
                .append("authority_created=false\n")
                .append("follow_up_ref=").append(m.followUpReference()).append("\n\n");
        for (MeetingRecord.Contribution c : m.contributions()) {
            out.append("[").append(c.role()).append("]\n").append(c.text()).append("\n\n");
        }
        out.append("[MEETING SYNTHESIS]\n").append(m.recommendation())
                .append("\n\nFollow-up: ").append(String.join(" ", m.actionItems()));
        return out.toString();
    }
}
