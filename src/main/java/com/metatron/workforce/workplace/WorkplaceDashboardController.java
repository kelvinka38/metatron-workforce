package com.metatron.workforce.workplace;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/workplace")
public class WorkplaceDashboardController {
    private final WorkplaceDashboardAuthService auth;
    private final WorkplaceDashboardService dashboard;

    public WorkplaceDashboardController(WorkplaceDashboardAuthService auth, WorkplaceDashboardService dashboard) {
        this.auth = auth; this.dashboard = dashboard;
    }

    @GetMapping(value={"", "/", "/index.html"}, produces=MediaType.TEXT_HTML_VALUE)
    public String page() { return HTML; }

    @GetMapping("/api/auth/status")
    public Map<String,Object> authStatus(@RequestHeader(value="Authorization", required=false) String authorization) {
        return Map.of("authenticated", auth.valid(bearer(authorization)));
    }

    @PostMapping("/api/auth/request-code")
    public ResponseEntity<?> requestCode() {
        try { auth.requestCode(); return ResponseEntity.accepted().body(Map.of("sent", true, "expiresInSeconds", 300)); }
        catch (IllegalStateException e) { return ResponseEntity.status(429).body(Map.of("sent", false, "error", e.getMessage())); }
    }

    @PostMapping("/api/auth/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String,String> body) {
        try { return ResponseEntity.ok(Map.of("token", auth.verify(body.get("code")), "expiresInSeconds", 43200)); }
        catch (SecurityException e) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_or_expired_code")); }
    }

    @PostMapping("/api/auth/logout")
    public Map<String,Boolean> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        auth.logout(bearer(authorization)); return Map.of("ok", true);
    }

    @GetMapping("/api/dashboard")
    public ResponseEntity<?> dashboard(@RequestHeader(value="Authorization", required=false) String authorization) {
        if (!auth.valid(bearer(authorization))) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "authentication_required"));
        return ResponseEntity.ok(dashboard.dashboard());
    }

    private static String bearer(String header) { return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null; }

    private static final String HTML = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0a0d12"><title>Metatron Control Room</title>
<style>
:root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#eef2f7;background:#0a0d12}
*{box-sizing:border-box}body{margin:0;background:#0a0d12;color:#eef2f7;min-height:100vh}
button,input{font:inherit}.hidden{display:none!important}
.shell{max-width:1440px;margin:auto;padding:20px}
.topbar{display:flex;align-items:center;justify-content:space-between;gap:18px;margin-bottom:18px}
.brand{font-weight:850;font-size:20px;letter-spacing:.02em}.sub{font-size:12px;color:#8a95a5;margin-top:3px}
.live{display:flex;align-items:center;gap:8px;font-size:12px;color:#9db0c6}.dot{width:8px;height:8px;border-radius:50%;background:#43d17d;box-shadow:0 0 12px rgba(67,209,125,.7)}
.actions{display:flex;align-items:center;gap:8px}.ghost{border:1px solid #2a3340;background:#11161d;color:#dce4ed;border-radius:10px;padding:8px 11px}
.kpis{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:10px;margin-bottom:14px}
.kpi{background:#11161d;border:1px solid #232c37;border-radius:14px;padding:14px}.kpi .label{font-size:11px;color:#7f8a99;text-transform:uppercase;letter-spacing:.08em}.kpi .value{font-size:26px;font-weight:850;margin-top:5px}.kpi .hint{font-size:11px;color:#697585;margin-top:3px}
.main{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(320px,.75fr);gap:12px}
.panel{background:#10151c;border:1px solid #232b35;border-radius:15px;overflow:hidden}.panelHead{padding:14px 15px;border-bottom:1px solid #232b35;display:flex;align-items:center;justify-content:space-between;gap:10px}.panelHead h2{margin:0;font-size:14px}.panelHead span{font-size:11px;color:#738091}
.tableWrap{overflow:auto}.objTable{width:100%;border-collapse:collapse;min-width:760px}.objTable th,.objTable td{text-align:left;padding:11px 13px;border-bottom:1px solid #1e2630;font-size:12px;vertical-align:middle}.objTable th{font-size:10px;text-transform:uppercase;letter-spacing:.08em;color:#778394;background:#0e1319;position:sticky;top:0}.objRow{cursor:pointer}.objRow:hover{background:#151c24}
.objTitle{font-weight:700;font-size:13px;max-width:520px}.muted{color:#7f8b9a}.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px}
.badge{display:inline-flex;align-items:center;padding:4px 7px;border-radius:999px;font-size:10px;font-weight:800;white-space:nowrap;background:#222b36;color:#b8c5d5}.WORKING,.COMPLETED_WITH_EVIDENCE,.ACTIVE,.COMPLETED{background:#163627;color:#72e5a4}.BLOCKED,.STALE_EXECUTION,.UNPROVEN_EXECUTION,.UNPROVEN_COMPLETION{background:#462026;color:#ff929c}.READY,.PLANNING,.PENDING_PLANNING,.VERIFYING,.PENDING_VERIFICATION,.WAITING{background:#3a321d;color:#f2d37b}
.progress{width:100%;height:6px;background:#202731;border-radius:999px;overflow:hidden;margin-top:5px}.progress i{display:block;height:100%;background:#7b8cff}
.sideStack{display:grid;gap:12px}.list{display:grid}.item{padding:12px 14px;border-bottom:1px solid #1f2731}.item:last-child{border-bottom:0}.itemTitle{font-size:12px;font-weight:700}.itemMeta{font-size:11px;color:#748091;margin-top:4px;line-height:1.45}
.drawerBackdrop{position:fixed;inset:0;background:rgba(0,0,0,.54);z-index:40}.drawer{position:fixed;z-index:41;top:0;right:0;width:min(760px,94vw);height:100vh;background:#0d1218;border-left:1px solid #2b3440;overflow:auto;box-shadow:-24px 0 50px rgba(0,0,0,.45)}.drawerHead{position:sticky;top:0;background:#0d1218e8;backdrop-filter:blur(12px);display:flex;justify-content:space-between;gap:16px;padding:17px;border-bottom:1px solid #252e39}.drawerHead h2{margin:0;font-size:16px}.close{border:1px solid #303a46;background:#161d25;color:#eef2f7;border-radius:9px;padding:7px 10px}.drawerBody{padding:16px}.detailGrid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;margin-bottom:14px}.detailBox{background:#121820;border:1px solid #202a35;border-radius:11px;padding:11px}.detailBox .k{font-size:10px;color:#748090;text-transform:uppercase}.detailBox .v{font-size:13px;font-weight:750;margin-top:4px}
.section{margin-top:16px}.section h3{font-size:12px;margin:0 0 8px;color:#aeb9c7;text-transform:uppercase;letter-spacing:.06em}.step{border:1px solid #222c37;background:#10161d;border-radius:11px;padding:11px;margin-bottom:8px}.stepTop{display:flex;justify-content:space-between;gap:12px}.stepName{font-size:12px;font-weight:700}.stepMeta{font-size:11px;color:#74808f;line-height:1.5;margin-top:6px}.evidence{font-size:11px;color:#a9b5c3;background:#0b1016;border:1px solid #202934;border-radius:9px;padding:9px;word-break:break-all;margin-bottom:6px}.event{display:grid;grid-template-columns:92px 1fr;gap:10px;padding:8px 0;border-bottom:1px solid #1f2730}.event:last-child{border-bottom:0}.event time{font-size:10px;color:#6f7b89}.event div{font-size:11px;color:#a7b3c1}
.login{max-width:420px;margin:14vh auto 0;padding:18px}.loginCard{background:#11171e;border:1px solid #26313c;border-radius:16px;padding:18px}.login input{width:100%;background:#0c1117;color:#fff;border:1px solid #2a3541;border-radius:10px;padding:13px;font-size:18px;letter-spacing:.14em;text-align:center;margin:10px 0}.primary{width:100%;border:0;border-radius:10px;padding:12px;font-weight:800;background:#e8edf3;color:#111821}.secondary{width:100%;margin-top:8px;border:1px solid #2b3540;border-radius:10px;padding:12px;font-weight:800;background:#17202a;color:#dce5ef}.error{color:#ff929c;font-size:12px;margin-top:8px}
.footer{font-size:10px;color:#5f6b79;text-align:center;margin:14px 0}
@media(max-width:1050px){.kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.main{grid-template-columns:1fr}.sideStack{grid-template-columns:1fr 1fr}.detailGrid{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:640px){.shell{padding:10px}.topbar{align-items:flex-start}.kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.kpi .value{font-size:23px}.sideStack{grid-template-columns:1fr}.detailGrid{grid-template-columns:1fr 1fr}.drawer{width:100vw}.objTable{min-width:690px}}
</style></head><body>
<div id="login" class="login"><div class="loginCard"><div class="brand">METATRON CONTROL ROOM</div><div class="sub">Founder access · live operational truth</div><p class="itemMeta">Login code is delivered only to the authorized Telegram account.</p><button class="primary" onclick="requestCode()">Send login code</button><input id="code" inputmode="numeric" maxlength="6" placeholder="000000" autocomplete="one-time-code"><button class="secondary" onclick="verify()">Open control room</button><div id="loginError" class="error"></div></div></div>

<div id="app" class="shell hidden">
  <div class="topbar"><div><div class="brand">METATRON CONTROL ROOM</div><div id="revision" class="sub"></div></div><div class="actions"><div class="live"><i class="dot"></i>LIVE · 2s</div><button class="ghost" onclick="load()">Refresh</button></div></div>
  <div id="kpis" class="kpis"></div>
  <div class="main">
    <section class="panel"><div class="panelHead"><h2>Objectives & task execution</h2><span>Tap a row for full work breakdown</span></div><div class="tableWrap"><table class="objTable"><thead><tr><th>Objective</th><th>Execution</th><th>Tasks</th><th>Progress</th><th>Owner</th><th>Last activity</th></tr></thead><tbody id="objectives"></tbody></table></div></section>
    <div class="sideStack">
      <section class="panel"><div class="panelHead"><h2>Exceptions</h2><span id="alertCount"></span></div><div id="alerts" class="list"></div></section>
      <section class="panel"><div class="panelHead"><h2>Workers</h2><span id="workerCount"></span></div><div id="workers" class="list"></div></section>
      <section class="panel"><div class="panelHead"><h2>Recent management activity</h2><span>recorded</span></div><div id="events" class="list"></div></section>
    </div>
  </div>
  <div id="stamp" class="footer"></div>
</div>

<div id="backdrop" class="drawerBackdrop hidden" onclick="closeDrawer()"></div>
<aside id="drawer" class="drawer hidden"><div class="drawerHead"><div><h2 id="drawerTitle"></h2><div id="drawerSub" class="sub"></div></div><button class="close" onclick="closeDrawer()">Close</button></div><div id="drawerBody" class="drawerBody"></div></aside>

<script>
const $=id=>document.getElementById(id);let token=localStorage.getItem('metatron_workplace_token')||'',snapshot=null,selected=null;
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtTime=v=>v?new Date(v).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'}):'—';
async function requestCode(){$('loginError').textContent='';let r=await fetch('/workplace/api/auth/request-code',{method:'POST'});let j=await r.json();$('loginError').textContent=r.ok?'Code sent. Check Telegram.':(j.error||'Unable to send code.')}
async function verify(){let r=await fetch('/workplace/api/auth/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:$('code').value})});let j=await r.json();if(!r.ok){$('loginError').textContent='Invalid or expired code.';return}token=j.token;localStorage.setItem('metatron_workplace_token',token);showApp();load()}
function showApp(){$('login').classList.add('hidden');$('app').classList.remove('hidden')}
function badge(s){return '<span class="badge '+esc(s)+'">'+esc(s)+'</span>'}
function kpi(label,value,hint){return '<div class="kpi"><div class="label">'+esc(label)+'</div><div class="value">'+esc(value)+'</div><div class="hint">'+esc(hint)+'</div></div>'}
async function load(){let r=await fetch('/workplace/api/dashboard',{headers:{Authorization:'Bearer '+token}});if(r.status===401){localStorage.removeItem('metatron_workplace_token');location.reload();return}snapshot=await r.json();render(snapshot);if(selected)openObjective(selected,true)}
function render(d){
  $('revision').textContent=d.environment+' · '+d.revision.slice(0,12);
  let s=d.summary;
  $('kpis').innerHTML=[
    kpi('Tasks running',s.runningTasks,'durable active execution'),
    kpi('Tasks completed',s.completedTasks,'accepted work steps'),
    kpi('Tasks waiting',s.waitingTasks,'ready or dependency wait'),
    kpi('Tasks blocked',s.blockedTasks,'needs recovery / input'),
    kpi('Objectives active',s.activeObjectives+' / '+s.objectives,'current / total'),
    kpi('Workers active',s.activeWorkers+' / '+s.workers,'admitted workforce')
  ].join('');
  $('objectives').innerHTML=d.objectivePulse.length?d.objectivePulse.map(o=>{
    let taskText=o.totalWork?o.completedWork+'/'+o.totalWork:'not planned';
    return '<tr class="objRow" onclick="openObjective(\''+esc(o.objectiveId)+'\')"><td><div class="objTitle">'+esc(o.summary)+'</div><div class="muted mono">'+esc(o.objectiveId)+'</div></td><td>'+badge(o.executionState)+(o.blocker?'<div class="muted">blocked</div>':'')+'</td><td>'+esc(taskText)+'</td><td><b>'+o.progressPercent+'%</b><div class="progress"><i style="width:'+o.progressPercent+'%"></i></div></td><td>'+esc(o.ownerWorkerId)+'<div class="muted">'+esc(o.staffingState)+'</div></td><td>'+fmtTime(o.lastActivityAt)+'</td></tr>'
  }).join(''):'<tr><td colspan="6" class="muted">No objectives yet.</td></tr>';
  $('alertCount').textContent=d.alerts.length+' active';$('alerts').innerHTML=d.alerts.length?d.alerts.slice(0,12).map(a=>'<div class="item"><div class="itemTitle">'+esc(a.detail)+'</div><div class="itemMeta">'+badge(a.status)+' · '+esc(a.type)+' · '+esc(a.ref)+'</div></div>').join(''):'<div class="item muted">No active exceptions.</div>';
  $('workerCount').textContent=d.workers.length+' total';$('workers').innerHTML=d.workers.length?d.workers.slice(0,14).map(w=>{let av=w.availability,p=w.participations?.[0];return '<div class="item"><div class="itemTitle">'+esc(w.workerId)+' '+badge(w.status)+'</div><div class="itemMeta">'+esc(p?.positionRef||p?.roleRef||'No position')+' · '+w.assignments.length+' assignments · capacity '+esc(av?av.capacity:'unknown')+'</div></div>'}).join(''):'<div class="item muted">No workers.</div>';
  $('events').innerHTML=d.recentEvents.length?d.recentEvents.slice(0,16).map(e=>'<div class="item"><div class="itemTitle">'+esc(e.type)+'</div><div class="itemMeta">'+fmtTime(e.occurredAt)+' · '+esc(e.actorWorkerId)+' · '+esc(e.detail)+'</div></div>').join(''):'<div class="item muted">No management activity.</div>';
  $('stamp').textContent='Recorded canonical management state · '+new Date(d.generatedAt).toLocaleString();
}
function openObjective(id,quiet=false){
  if(!snapshot)return;let o=snapshot.objectivePulse.find(x=>x.objectiveId===id);if(!o)return;selected=id;
  $('drawerTitle').textContent=o.summary;$('drawerSub').textContent=o.objectiveId+' · '+o.ownerWorkerId;
  let boxes=[
    ['Execution',badge(o.executionState)],['Progress',o.progressPercent+'%'],['Tasks',o.completedWork+' / '+o.totalWork],['Evidence',o.evidenceCount],
    ['Staffing',o.staffingState],['Objective state',o.objectiveStatus],['Last activity',fmtTime(o.lastActivityAt)],['Blocker',o.blocker||'None']
  ].map(x=>'<div class="detailBox"><div class="k">'+esc(x[0])+'</div><div class="v">'+(String(x[1]).startsWith('<span')?x[1]:esc(x[1]))+'</div></div>').join('');
  let steps=o.workItems.length?o.workItems.map((w,i)=>'<div class="step"><div class="stepTop"><div class="stepName">'+(i+1)+'. '+esc(w.objective)+'</div>'+badge(w.state)+'</div><div class="stepMeta">Capability: '+esc(w.requiredCapability)+'<br>Target: '+esc(w.target||'—')+'<br>Depends: '+esc(w.dependsOn.join(', ')||'none')+'<br>DoD: '+esc(w.acceptanceCriteria.join('; ')||'NOT DEFINED')+'<br>Evidence required: '+esc(w.evidenceRequirements.join('; ')||'NOT DEFINED')+'</div></div>').join(''):'<div class="muted">Planning has not produced work items yet.</div>';
  let ev=o.recentEvents.length?o.recentEvents.map(e=>'<div class="event"><time>'+fmtTime(e.at)+'</time><div><b>'+esc(e.type)+'</b> · '+esc(e.actor)+'<br>'+esc(e.detail)+'</div></div>').join(''):'<div class="muted">No objective events.</div>';
  let evidence=o.evidenceReferences.length?o.evidenceReferences.map(e=>'<div class="evidence">'+esc(e)+'</div>').join(''):'<div class="muted">No evidence attached yet.</div>';
  $('drawerBody').innerHTML='<div class="detailGrid">'+boxes+'</div><div class="section"><h3>Work breakdown</h3>'+steps+'</div><div class="section"><h3>Execution evidence</h3>'+evidence+'</div><div class="section"><h3>Activity history</h3>'+ev+'</div>';
  $('drawer').classList.remove('hidden');$('backdrop').classList.remove('hidden');if(!quiet)history.replaceState(null,'','#objective='+encodeURIComponent(id));
}
function closeDrawer(){selected=null;$('drawer').classList.add('hidden');$('backdrop').classList.add('hidden');history.replaceState(null,'',location.pathname)}
(async()=>{if(token){let r=await fetch('/workplace/api/auth/status',{headers:{Authorization:'Bearer '+token}});let j=await r.json();if(j.authenticated){showApp();await load();setInterval(load,2000);let id=new URLSearchParams(location.hash.replace('#','')).get('objective');if(id)openObjective(id)}}})();
</script></body></html>
""";

}
