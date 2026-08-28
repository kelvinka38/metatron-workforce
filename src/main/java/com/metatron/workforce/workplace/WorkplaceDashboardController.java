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
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#081018"><title>Metatron Workplace</title>
<style>
:root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#e8eef5;background:#081018}*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#081018,#0d1722);min-height:100vh}.wrap{max-width:1280px;margin:auto;padding:18px}.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}.brand{font-size:20px;font-weight:800;letter-spacing:.02em}.sub{color:#8291a5;font-size:12px}.live{display:flex;align-items:center;gap:7px;font-size:12px}.dot{width:9px;height:9px;border-radius:50%;background:#31d07f;box-shadow:0 0 16px #31d07f}.grid{display:grid;gap:12px}.stats{grid-template-columns:repeat(5,minmax(0,1fr));margin-bottom:12px}.card{background:rgba(17,29,42,.92);border:1px solid #223448;border-radius:16px;padding:14px;box-shadow:0 10px 30px rgba(0,0,0,.18)}.k{color:#8291a5;font-size:11px;text-transform:uppercase;letter-spacing:.08em}.v{font-size:28px;font-weight:800;margin-top:3px}.section{margin-top:12px}.section h2{font-size:14px;margin:0 0 10px}.two{grid-template-columns:1.2fr .8fr}.list{display:grid;gap:8px}.row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:10px;padding:11px;background:#0d1823;border-radius:11px;border:1px solid #1a2b3c}.title{font-weight:700;font-size:13px}.meta{font-size:11px;color:#8191a4;margin-top:4px;overflow-wrap:anywhere}.badge{font-size:10px;font-weight:800;padding:5px 8px;border-radius:999px;background:#203245;height:max-content}.ACTIVE,.IN_PROGRESS,.AVAILABLE,.DELIVERED,.COMPLETED{background:#123b2b;color:#6ee7a8}.BLOCKED,.ESCALATED,.NO_CAPACITY{background:#4a2024;color:#ff8d98}.ASSIGNED,.PROPOSED,.ORIGINATED{background:#223453;color:#8db8ff}.empty{padding:18px;color:#728399;text-align:center}.login{max-width:420px;margin:15vh auto 0}.login input{width:100%;background:#0b151f;color:white;border:1px solid #2a3d51;border-radius:11px;padding:14px;font-size:18px;letter-spacing:.15em;text-align:center;margin:10px 0}.btn{width:100%;border:0;border-radius:11px;padding:13px;font-weight:800;background:#e7edf4;color:#101820}.btn.secondary{margin-top:8px;background:#192a3b;color:#d9e3ed}.error{color:#ff8d98;font-size:12px;margin-top:8px}.hidden{display:none!important}.refresh{border:1px solid #2b4055;background:#142334;color:#dbe6f0;border-radius:10px;padding:8px 11px;font-size:12px}.workers{grid-template-columns:repeat(2,minmax(0,1fr))}.worker{padding:13px;background:#0d1823;border-radius:12px}.workerhead{display:flex;justify-content:space-between;gap:8px}.capacity{height:5px;background:#172635;border-radius:4px;margin-top:9px;overflow:hidden}.capacity i{display:block;height:100%;background:#31d07f}.footer{color:#66788d;font-size:10px;margin:18px 0;text-align:center}@media(max-width:760px){.wrap{padding:12px}.stats{grid-template-columns:repeat(2,minmax(0,1fr))}.stats .card:last-child{grid-column:1/-1}.two,.workers{grid-template-columns:1fr}.v{font-size:24px}.top{align-items:flex-start}}
</style></head><body>
<div id="login" class="wrap login"><div class="card"><div class="brand">METATRON WORKPLACE</div><div class="sub">Founder Management Dashboard · Live Workforce</div><p class="meta">Login is delivered only to the authorized Telegram account.</p><button class="btn" onclick="requestCode()">Send login code to Telegram</button><input id="code" inputmode="numeric" maxlength="6" placeholder="000000" autocomplete="one-time-code"><button class="btn secondary" onclick="verify()">Open dashboard</button><div id="loginError" class="error"></div></div></div>
<div id="app" class="wrap hidden"><div class="top"><div><div class="brand">METATRON WORKPLACE</div><div id="revision" class="sub"></div></div><div><div class="live"><i class="dot"></i>PRODUCTION LIVE</div><button class="refresh" onclick="load()">Refresh</button></div></div>
<div id="stats" class="grid stats"></div><div class="grid two"><div class="card"><h2>Objectives</h2><div id="objectives" class="list"></div></div><div class="card"><h2>Alerts / Exceptions</h2><div id="alerts" class="list"></div></div></div>
<div class="section card"><h2>Workforce</h2><div id="workers" class="grid workers"></div></div><div class="section card"><h2>Current Work</h2><div id="work" class="list"></div></div><div class="section card"><h2>Recent Management Activity</h2><div id="events" class="list"></div></div><div id="stamp" class="footer"></div></div>
<script>
const $=id=>document.getElementById(id);let token=localStorage.getItem('metatron_workplace_token')||'';const esc=s=>String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));
async function requestCode(){ $('loginError').textContent='';let r=await fetch('/workplace/api/auth/request-code',{method:'POST'});let j=await r.json();$('loginError').textContent=r.ok?'Code sent. Check Telegram.':(j.error||'Unable to send code.');}
async function verify(){let r=await fetch('/workplace/api/auth/verify',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:$('code').value})});let j=await r.json();if(!r.ok){$('loginError').textContent='Invalid or expired code.';return}token=j.token;localStorage.setItem('metatron_workplace_token',token);showApp();load()}
function showApp(){$('login').classList.add('hidden');$('app').classList.remove('hidden')}
function badge(s){return `<span class="badge ${esc(s)}">${esc(s)}</span>`}function empty(t){return `<div class="empty">${esc(t)}</div>`}
function row(title,meta,status){return `<div class="row"><div><div class="title">${esc(title)}</div><div class="meta">${esc(meta)}</div></div>${badge(status)}</div>`}
async function load(){let r=await fetch('/workplace/api/dashboard',{headers:{Authorization:'Bearer '+token}});if(r.status===401){localStorage.removeItem('metatron_workplace_token');location.reload();return}let d=await r.json();render(d)}
function render(d){$('revision').textContent=`${d.environment} · ${d.revision.slice(0,12)}`;let s=d.summary;let stats=[['Workers',`${s.activeWorkers}/${s.workers}`],['Objectives',`${s.activeObjectives}/${s.objectives}`],['Active work',`${s.activeWork}/${s.workItems}`],['Assignments',s.assignments],['Alerts',s.alerts]];$('stats').innerHTML=stats.map(x=>`<div class="card"><div class="k">${x[0]}</div><div class="v">${x[1]}</div></div>`).join('');
$('objectives').innerHTML=d.objectives.length?d.objectives.map(o=>row(o.description,`${o.objectiveId} · owner ${o.ownerWorkerId} · ${o.assignmentRefs.length} assignments`,o.status)).join(''):empty('No objectives yet');
$('alerts').innerHTML=d.alerts.length?d.alerts.map(a=>row(a.detail,`${a.type} · ${a.ref}`,a.status)).join(''):empty('No active exceptions');
$('workers').innerHTML=d.workers.length?d.workers.map(w=>{let p=w.participations?.[0],av=w.availability,cap=av?Math.max(0,Math.min(100,av.capacity*100)):0;return `<div class="worker"><div class="workerhead"><div><div class="title">${esc(w.workerId)}</div><div class="meta">${esc(p?.positionRef||p?.roleRef||'No position')} · ${w.assignments.length} assignments · ${w.capabilities.length} capabilities</div></div>${badge(w.status)}</div><div class="capacity"><i style="width:${cap}%"></i></div><div class="meta">Capacity ${av?esc(av.capacity):'not reported'} · ${av?(av.available?'available':'unavailable'):'unknown'}</div></div>`}).join(''):empty('No Workers admitted yet');
$('work').innerHTML=d.work.length?d.work.slice(0,30).map(w=>row(w.description,`${w.workId} · objective ${w.objectiveRef} · by ${w.originatedByWorkerId}`,w.status)).join(''):empty('No Work yet');
$('events').innerHTML=d.recentEvents.length?d.recentEvents.slice(0,30).map(e=>row(e.type,`${e.actorWorkerId} · ${e.objectiveId} · ${e.detail}`,e.type.includes('BLOCK')||e.type.includes('ESCALAT')?'BLOCKED':'ACTIVE')).join(''):empty('No management activity yet');$('stamp').textContent='Live snapshot · '+new Date(d.generatedAt).toLocaleString();}
(async()=>{if(token){let r=await fetch('/workplace/api/auth/status',{headers:{Authorization:'Bearer '+token}});let j=await r.json();if(j.authenticated){showApp();load();setInterval(load,10000)}}})();
</script></body></html>
""";
}
