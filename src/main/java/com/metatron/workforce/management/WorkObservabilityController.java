package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Human-facing read-only projection of canonical Workforce management state. */
@RestController
@RequestMapping("/workforce/monitor")
public final class WorkObservabilityController {
    private static final Duration LIVE_ACTIVITY_WINDOW = Duration.ofSeconds(90);
    private final ManagementAutonomyService management;
    private final WorkforceCoreService core;

    public WorkObservabilityController(ManagementAutonomyService management, WorkforceCoreService core) {
        this.management = management;
        this.core = core;
    }

    @GetMapping(value = "", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboard() { return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(DASHBOARD_HTML); }

    @GetMapping("/api/objectives")
    public List<ObjectiveMonitorView> objectives(@RequestHeader("X-Metatron-Actor") String actor) {
        return management.allObjectives().stream().filter(objective -> authorized(actor, objective)).map(this::view).toList();
    }

    @GetMapping("/api/objectives/{objectiveId}")
    public ObjectiveMonitorView objective(@PathVariable String objectiveId, @RequestHeader("X-Metatron-Actor") String actor) {
        ManagementObjective objective;
        try { objective = management.get(objectiveId); }
        catch (IllegalArgumentException missing) { throw new ResponseStatusException(NOT_FOUND, "objective not found"); }
        if (!authorized(actor, objective)) throw new ResponseStatusException(FORBIDDEN, "objective access denied");
        return view(objective);
    }

    private boolean authorized(String actor, ManagementObjective objective) {
        if (actor == null || actor.isBlank()) return false;
        String normalized = actor.startsWith("human:") ? actor.substring("human:".length()) : actor;
        if (objective.ownerWorkerId().equals(actor)) return true;
        return management.findAutonomousWork(objective.objectiveId()).map(work -> work.humanId().equals(normalized)).orElse(false);
    }

    private ObjectiveMonitorView view(ManagementObjective objective) {
        AutonomousObjectiveWork work = management.findAutonomousWork(objective.objectiveId()).orElse(null);
        List<ManagementAutonomyService.ManagementEvent> history = management.history(objective.objectiveId());
        Instant lastActivity = history.stream().map(ManagementAutonomyService.ManagementEvent::occurredAt)
                .max(Comparator.naturalOrder()).orElse(objective.updatedAt());
        boolean recentActivity = Duration.between(lastActivity, Instant.now()).compareTo(LIVE_ACTIVITY_WINDOW) <= 0;
        boolean staffed = !objective.assignmentRefs().isEmpty();
        // Truthful attribution from durable Assignment/execution attribution -- never inferred from
        // requested capability or Worker name in the Objective. Real even before/without step-level
        // execution evidence, and remains real historical truth after failure/cancellation.
        List<String> durablePerformers = WorkCardRenderer.durableAssignmentPerformers(core, objective);
        String durablePerformerLabel = String.join(", ", durablePerformers);

        List<WorkItemView> items = new ArrayList<>(); int completed = 0; int total = 0;
        if (work != null) {
            Set<String> completedIds = Set.copyOf(work.completedStepIds()); total = work.plannedWork().size(); completed = completedIds.size();
            for (ExecutionWorkSpec step : work.plannedWork()) {
                String state;
                if (completedIds.contains(step.stepId())) state = "COMPLETED";
                else if (!completedIds.containsAll(step.dependsOn())) state = "WAITING_DEPENDENCY";
                else if (work.status() == AutonomousObjectiveWork.Status.EXECUTING) state = "READY_OR_RUNNING";
                else if (work.status() == AutonomousObjectiveWork.Status.BLOCKED) state = "BLOCKED";
                else state = "READY";
                String performer = !durablePerformers.isEmpty() ? durablePerformerLabel
                        : staffed ? "SEE_ASSIGNMENT_EVIDENCE" : "UNASSIGNED";
                items.add(new WorkItemView(step.stepId(), step.objective(), step.target(), step.requiredCapability(),
                        performer, step.dependsOn(), state,
                        step.acceptanceCriteria(), step.evidenceRequirements()));
            }
        }

        int progress = total == 0 ? (objective.terminal() ? 100 : 0) : (int)Math.floor(completed * 100.0 / total);
        String executionProof = executionProof(work, history, recentActivity);
        String humanStatus = humanStatus(objective, work, executionProof);
        List<EventView> recentEvents = history.stream().sorted(Comparator.comparing(ManagementAutonomyService.ManagementEvent::occurredAt).reversed())
                .limit(20).map(event -> new EventView(event.type().name(), event.actorWorkerId(), event.detail(), event.occurredAt())).toList();
        List<String> evidence = work == null ? objective.evidenceRefs() : work.evidenceReferences();
        String blocker = work == null ? "" : work.blocker();
        String reportsTo = work == null ? "NOT_MATERIALIZED" : "human:" + work.humanId();
        String workload = total == 0 ? "PLANNING_PENDING" : total + "_WORK_ITEMS";
        String eta = work != null && work.terminal() ? "COMPLETED" : "NOT_COMMITTED";
        // Never claim UNASSIGNED while a real Assignment is referenced, even if step-level execution
        // evidence has not landed yet (execution running, or failed/cancelled and left as historical
        // attribution).
        String staffing = !staffed ? "UNASSIGNED" : !durablePerformers.isEmpty() ? "ASSIGNED:" + durablePerformerLabel : "ASSIGNMENT_EVIDENCE_PRESENT";

        Map<String,String> proof = new LinkedHashMap<>(); proof.put("state",executionProof); proof.put("last_activity_at",lastActivity.toString());
        proof.put("activity_fresh",Boolean.toString(recentActivity)); proof.put("work_version",work==null?"0":Integer.toString(work.version())); proof.put("evidence_count",Integer.toString(evidence.size()));

        return new ObjectiveMonitorView(objective.objectiveId(), concise(objective.description()), objective.ownerWorkerId(), reportsTo,
                workload, eta, staffing, objective.status().name(), work==null?"NONE":work.status().name(), humanStatus,
                progress, completed, total, blocker, lastActivity, work==null?objective.createdAt():work.createdAt(),
                objective.assignmentRefs(), evidence, items, recentEvents, proof, durablePerformers);
    }

    private static String executionProof(AutonomousObjectiveWork work, List<ManagementAutonomyService.ManagementEvent> history, boolean recentActivity) {
        if (work == null) return "NO_AUTONOMOUS_WORK";
        if (work.status() == AutonomousObjectiveWork.Status.COMPLETED) return work.evidenceReferences().isEmpty()?"TERMINAL_WITHOUT_EVIDENCE":"TERMINAL_EVIDENCE_PRESENT";
        if (work.status() != AutonomousObjectiveWork.Status.EXECUTING) return "NOT_EXECUTING";
        boolean executionStarted = history.stream().anyMatch(e -> e.type()==ManagementAutonomyService.ManagementEvent.Type.EXECUTION_STARTED);
        boolean material = history.stream().anyMatch(e -> e.type()==ManagementAutonomyService.ManagementEvent.Type.EXECUTION_STARTED || e.type()==ManagementAutonomyService.ManagementEvent.Type.WORK_STEP_COMPLETED);
        if (!executionStarted || !material) return "EXECUTION_CLAIM_UNPROVEN";
        return recentActivity ? "EXECUTION_ACTIVITY_OBSERVED" : "EXECUTION_STALE";
    }

    private static String humanStatus(ManagementObjective objective, AutonomousObjectiveWork work, String proof) {
        if (objective.terminal()) return objective.status().name(); if (work==null) return objective.status().name();
        return switch(work.status()) { case PENDING_PLANNING->"RECEIVED"; case PLANNING->"PLANNING"; case READY->"READY";
            case EXECUTING->"EXECUTION_ACTIVITY_OBSERVED".equals(proof)?"WORKING":"WAITING_FOR_EXECUTION_PROOF";
            case PENDING_VERIFICATION->"PENDING_VERIFICATION"; case VERIFYING->"VERIFYING"; case BLOCKED->"BLOCKED"; case COMPLETED->"COMPLETED"; case CANCELLED->"CANCELLED"; };
    }

    private static String concise(String description) { if(description==null)return ""; String s=description.replaceAll("\\s+"," ").trim(); return s.length()<=180?s:s.substring(0,177)+"..."; }

    public record WorkItemView(String stepId,String objective,String target,String requiredCapability,String performer,
            List<String> dependsOn,String status,List<String> acceptanceCriteria,List<String> evidenceRequirements) {}
    public record EventView(String type,String actor,String detail,Instant at) {}
    public record ObjectiveMonitorView(String objectiveId,String summary,String ownerWorker,String reportsTo,String workload,
            String etaCommitment,String staffingState,String objectiveStatus,String workStatus,String humanStatus,int progressPercent,
            int completedWork,int totalWork,String blocker,Instant lastActivityAt,Instant startedAt,List<String> assignmentReferences,
            List<String> evidenceReferences,List<WorkItemView> workItems,List<EventView> recentEvents,Map<String,String> executionProof,
            List<String> durablePerformerWorkerIds) {}

    private static final String DASHBOARD_HTML = """
<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Metatron Workforce · Live Work</title>
<style>:root{color-scheme:dark;font-family:Inter,system-ui,sans-serif;background:#0b0d12;color:#f4f5f7}body{margin:0}.top{position:sticky;top:0;background:#0b0d12e8;padding:18px 22px;border-bottom:1px solid #262a33}.brand{font-weight:800}.live,.ok{color:#6ee7a8}.wrap{max-width:1200px;margin:auto;padding:20px}.toolbar{display:flex;gap:12px;margin-bottom:18px}.toolbar input{background:#141821;border:1px solid #303644;color:#fff;padding:10px;border-radius:10px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:14px}.card,.detail{background:#121620;border:1px solid #252b36;border-radius:16px;padding:16px}.card{cursor:pointer}.row{display:flex;justify-content:space-between;gap:12px}.muted{color:#9299a8;font-size:12px}.status{font-weight:700}.bar{height:7px;background:#242a35;border-radius:6px;overflow:hidden;margin:12px 0}.fill{height:100%;background:#8c7cff}.detail{display:none;margin-top:18px}.work{width:100%;border-collapse:collapse}.work th,.work td{text-align:left;padding:9px;border-bottom:1px solid #252b36;font-size:12px}.proof{background:#0c1017;padding:12px;border-radius:10px;font:12px ui-monospace}.warn{color:#ffd479}.bad{color:#ff8080}.meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px;margin:12px 0}.meta div{background:#0d1118;padding:10px;border-radius:9px}@media(max-width:640px){.wrap{padding:10px}.work{display:block;overflow-x:auto}}</style></head>
<body><div class='top'><span class='brand'>METATRON WORKFORCE</span> <span class='live'>● LIVE</span></div><div class='wrap'><div class='toolbar'><input id='actor' value='human-primary'><span class='muted'>Live refresh every 2 seconds · canonical management projection</span></div><div id='grid' class='grid'></div><div id='detail' class='detail'></div></div>
<script>const actor=document.getElementById('actor'),grid=document.getElementById('grid'),detail=document.getElementById('detail');let selected=null;const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));function cls(s){return /COMPLETED|OBSERVED|WORKING/.test(s)?'ok':/BLOCKED|STALE|UNPROVEN|UNASSIGNED/.test(s)?'bad':'warn'}async function api(p){const r=await fetch(p,{headers:{'X-Metatron-Actor':actor.value.trim()}});if(!r.ok)throw new Error(await r.text());return r.json()}function card(o){return `<div class='card' data-id='${esc(o.objectiveId)}'><div class='row'><b class='${cls(o.humanStatus)}'>${esc(o.humanStatus)}</b><span>${o.progressPercent}%</span></div><p>${esc(o.summary)}</p><div class='bar'><div class='fill' style='width:${o.progressPercent}%'></div></div><div class='muted'>${o.completedWork}/${o.totalWork} work · owner ${esc(o.ownerWorker)} · staffing <span class='${cls(o.staffingState)}'>${esc(o.staffingState)}</span></div></div>`}function detailHtml(o){let rows=o.workItems.map(w=>`<tr><td>${esc(w.objective)}</td><td class='${cls(w.status)}'>${esc(w.status)}</td><td>${esc(w.requiredCapability)}</td><td class='${cls(w.performer)}'>${esc(w.performer)}</td><td>${esc(w.acceptanceCriteria.join('; ')||'NOT DEFINED')}</td><td>${esc(w.dependsOn.join(', ')||'—')}</td></tr>`).join('');let ev=o.recentEvents.map(e=>`<div class='muted'>${new Date(e.at).toLocaleTimeString()} · ${esc(e.type)} · ${esc(e.actor)} · ${esc(e.detail)}</div>`).join('');return `<div class='row'><h2>WORK ORDER</h2><button onclick='selected=null;detail.classList.remove("open")'>Close</button></div><h3>${esc(o.summary)}</h3><div class='meta'><div>Status<br><b class='${cls(o.humanStatus)}'>${esc(o.humanStatus)}</b></div><div>Owner<br><b>${esc(o.ownerWorker)}</b></div><div>Reports to<br><b>${esc(o.reportsTo)}</b></div><div>Workload<br><b>${esc(o.workload)}</b></div><div>ETA<br><b>${esc(o.etaCommitment)}</b></div><div>Staffing<br><b class='${cls(o.staffingState)}'>${esc(o.staffingState)}</b></div></div><div class='proof'>execution=${esc(o.executionProof.state)} · last=${esc(o.executionProof.last_activity_at)} · evidence=${esc(o.executionProof.evidence_count)}</div>${o.blocker?`<p class='bad'>NOTE/BLOCKER: ${esc(o.blocker)}</p>`:'<p class=muted>NOTE/RISK: no active blocker recorded.</p>'}<h3>Work Breakdown</h3><table class='work'><thead><tr><th>Task</th><th>Status</th><th>Role</th><th>Performer</th><th>DoD</th><th>Depends</th></tr></thead><tbody>${rows||'<tr><td colspan=6>Planning pending</td></tr>'}</tbody></table><h3>Live activity</h3>${ev||'<div class=muted>No management events.</div>'}`}
async function refresh(){try{const d=await api('/workforce/monitor/api/objectives');grid.innerHTML=d.map(card).join('')||'<div>No Objectives.</div>';grid.querySelectorAll('.card').forEach(el=>el.onclick=()=>{selected=el.dataset.id;refreshDetail()});if(selected)await refreshDetail()}catch(e){grid.innerHTML=`<div class=bad>${esc(e.message)}</div>`}}async function refreshDetail(){if(!selected)return;const o=await api('/workforce/monitor/api/objectives/'+encodeURIComponent(selected));detail.innerHTML=detailHtml(o);detail.classList.add('open')}actor.onchange=()=>{selected=null;detail.classList.remove('open');refresh()};refresh();setInterval(refresh,2000);</script></body></html>
""";
}
