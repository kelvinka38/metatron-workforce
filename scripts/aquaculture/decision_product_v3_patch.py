#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()


def read(rel: str) -> tuple[Path, str]:
    p = root / rel
    if not p.exists():
        raise SystemExit(f"missing file: {p}")
    return p, p.read_text(encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"missing start anchor: {label}")
    b = text.find(end, a)
    if b < 0:
        raise SystemExit(f"missing end anchor: {label}")
    return text[:a] + replacement.rstrip() + "\n" + text[b:]


# Backend correctness: source-water operating requirements are not physical infrastructure.
p, s = read("bios_runtime/domains/aquaculture/synthesis.py")
s = replace_once(
    s,
    '    infrastructure=list(dict.fromkeys(method["infrastructure"]+source["requirements"]))',
    '    # Physical infrastructure and source-water operating requirements are separate.\n'
    '    # Keeping them separate prevents duplicate/false infrastructure facts in the farmer UI.\n'
    '    infrastructure=list(dict.fromkeys(method["infrastructure"]))',
    "synthesis infrastructure separation",
)
p.write_text(s, encoding="utf-8")


# Farmer decision UX/intelligence surface.
p, s = read("bios_runtime/domains/aquaculture/web/app.js")
idx = s.find("const html=v=>")
if idx < 0:
    raise SystemExit("missing app human-language insertion anchor")
line_end = s.find("\n", idx)
if line_end < 0:
    raise SystemExit("missing app html line ending")

human_block = r'''const humanMap={
 MANAGED_OPEN_WATER_SOURCE:"Nguồn nước mở có kiểm soát",MANAGED_COASTAL_SOURCE:"Nguồn nước ven biển có kiểm soát",MANAGED_GROUNDWATER_SOURCE:"Nguồn nước ngầm có kiểm soát",SOURCE_SPECIFIC_PLAN_REQUIRED:"Cần thiết kế riêng cho nguồn nước này",SOURCE_UNRESOLVED:"Chưa xác định nguồn nước",
 MONOCULTURE:"Nuôi đơn loài",POLYCULTURE:"Nuôi kết hợp",LOW:"Thấp",LOW_TO_MEDIUM:"Thấp → trung bình",MEDIUM:"Trung bình",MEDIUM_TO_HIGH:"Trung bình → cao",HIGH:"Cao",UNKNOWN:"Chưa rõ",
 PARTIAL_REFERENCE:"Có dữ liệu tham chiếu một phần",CURRENT_COST_REFERENCE:"Có tham chiếu chi phí hiện hành",INSUFFICIENT_EVIDENCE:"Chưa có benchmark phù hợp",
 COARSE_COMPATIBILITY_ONLY:"Mới xác nhận phù hợp sinh học ở mức nền",UNRESOLVED_UNTIL_FARM/CYCLE_EVIDENCE:"Chưa đủ dữ liệu thực tế của ao/vụ",SOURCE_DEPENDENT:"Phụ thuộc biến động nguồn nước",UNVERIFIED_AT_FARM:"Chưa kiểm chứng tại ao",UNRESOLVED:"Chưa đủ dữ liệu",MODELLED_WITH_CURRENT_COST_REFERENCE:"Đã có mô hình theo chi phí hiện hành",
 CURRENT:"Còn mới",STALE:"Đã cũ",
 "culture pond":"Ao nuôi","earthen pond/culture area":"Ao đất / khu nuôi","controllable water exchange path":"Đường cấp–thoát nước có thể kiểm soát","controllable inlet/outlet or documented exchange path":"Đường cấp–thoát nước có thể kiểm soát và có quy trình","harvest access":"Lối tiếp cận để thu hoạch","natural-food/input management capability":"Khả năng quản lý thức ăn tự nhiên và đầu vào","feeding/input handling":"Khả năng quản lý thức ăn và đầu vào","routine observation capability":"Khả năng theo dõi định kỳ","extensive culture area":"Khu nuôi quảng canh","culture pond/area":"Ao / khu nuôi","managed water exchange":"Hệ thống thay nước có kiểm soát","intensive culture unit":"Đơn vị nuôi thâm canh","reliable water/air management":"Quản lý nước và oxy ổn định","backup capability for critical equipment":"Nguồn dự phòng cho thiết bị quan trọng","high-frequency observation capability":"Khả năng theo dõi tần suất cao","suitable cage site":"Vị trí đặt lồng phù hợp","anchoring/mooring":"Hệ thống neo lồng","access for feed and harvest":"Lối tiếp cận cho cho ăn và thu hoạch","site-specific flow/weather safety evidence":"Bằng chứng an toàn dòng chảy và thời tiết tại điểm nuôi","tank system":"Hệ thống bể","recirculation/filtration":"Tuần hoàn và lọc nước","continuous power":"Nguồn điện liên tục","water-quality observation capability":"Khả năng theo dõi chất lượng nước",
 "verify current source-water condition before stocking":"Kiểm tra chất lượng nước nguồn trước khi thả","document intake/exchange operation":"Ghi rõ cách lấy và thay nước","document inlet/outlet operation":"Ghi rõ cách vận hành cấp–thoát nước","observe upstream/tidal/weather-driven source changes where relevant":"Theo dõi biến động thượng nguồn, thủy triều và thời tiết có thể ảnh hưởng nguồn nước","observe source changes that can propagate into the culture area":"Theo dõi biến động nguồn nước có thể truyền vào ao","verify current salinity/source condition":"Kiểm tra độ mặn và tình trạng nước nguồn hiện tại","document exchange/intake path":"Ghi rõ đường lấy và trao đổi nước","observe weather/tidal source changes where relevant":"Theo dõi biến động thời tiết và thủy triều","verify usable water quality":"Xác minh chất lượng nước có thể sử dụng","verify sustainable supply/capacity":"Xác minh lưu lượng cấp nước đủ và ổn định","document pumping/energy dependency":"Ghi rõ phụ thuộc vào bơm và năng lượng","document source identity and supply path":"Ghi rõ nguồn nước và đường cấp nước","resolve water source before operational planning":"Xác minh nguồn nước trước khi lập kế hoạch vận hành",
 "record actual stocking/harvest identity":"Ghi nhận giống thả và lô thu hoạch thực tế","record material inputs and costs":"Ghi nhận đầu vào và chi phí thực tế","observe deviations against the committed plan":"Theo dõi sai lệch so với kế hoạch đã chốt","operate critical equipment reliably":"Đảm bảo thiết bị quan trọng vận hành ổn định","maintain backup/recovery procedure":"Có phương án dự phòng và phục hồi"
};
function human(v){
 if(v==null||v==="")return "Chưa rõ";
 const k=String(v);if(humanMap[k])return humanMap[k];
 if(k.includes("_")){const x=k.toLowerCase().replaceAll("_"," ");return x.charAt(0).toUpperCase()+x.slice(1)}
 return k;
}
const html=v=>String(v??"").replace(/[&<>"']/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[m]));'''
s = s[:idx] + human_block + s[line_end:]
s = replace_once(s, 'const label=v=>vi[v]||v||"Chưa rõ";', 'const label=v=>vi[v]||human(v);', "humanized labels")

scenario_block = r'''function scenarioStatus(s){return s.eligibility==="ELIGIBLE"?"ok":s.eligibility==="INELIGIBLE"?"bad":"warn"}
function rangeText(x,unit=""){return x&&x.min!=null&&x.max!=null?num(x.min)+"–"+num(x.max)+(unit?" "+unit:""):"Chưa có benchmark phù hợp"}
function scenarioReady(s){return s.eligibility==="ELIGIBLE"&&Number(s.blocking_unknowns||0)===0&&s.recommendation_state==="FINAL_FOR_CURRENT_EVIDENCE"}
function scenarioSupport(s){
 if(scenarioReady(s))return {label:"Có thể chốt",tone:"ok"};
 if(s.eligibility==="INELIGIBLE")return {label:"Loại",tone:"bad"};
 const c=Number(s.economic_completeness||0);
 if(c>=2)return {label:"Có benchmark · còn thiếu dữ liệu",tone:"warn"};
 if(c>=1)return {label:"Tham chiếu một phần",tone:"warn"};
 return {label:"Mới ở mức cấu trúc",tone:"info"};
}
function scenarioVolume(s){return rangeText(s.production_volume_kg_range,"kg")}
function scenarioCost(s){
 if(s.capital_required_minor)return money(s.capital_required_minor);
 const h=s.historical_cycle_cost_reference_minor_range;
 if(h){const y=h.reference_year||s.economics?.reference_context?.reference_year;return (y?"Tham chiếu "+y+": ":"Tham chiếu lịch sử: ")+money(h.min)+"–"+money(h.max)}
 return "Chưa có mô hình chi phí phù hợp";
}
function cardReason(s){
 const gates=s.gate_results||[];
 const fail=gates.find(g=>g.result==="FAIL");
 if(fail)return humanGate(fail).text;
 if(s.production_volume_kg_range)return "Có benchmark sản lượng cho đúng cấu hình này; ưu tiên kiểm chứng các biến còn thiếu trước khi chốt.";
 if(Number(s.economic_completeness||0)>0)return "Có một phần dữ liệu vận hành/kinh tế, nhưng chưa đủ để coi là phương án hoàn chỉnh.";
 return "Cấu hình có cơ sở sinh học/hệ thống, nhưng chưa có benchmark vận hành đủ khớp để khuyến nghị mạnh.";
}
function nextAction(u){
 const k=String(u?.property||"").split(":").pop();
 const m={
  current_capital_model:"Lập chi phí hiện hành cho đúng ao và cấu hình này: hạ tầng, OPEX vụ, vốn lưu động và dự phòng.",capital_required_minor:"Lập chi phí hiện hành cho đúng ao và cấu hình này.",
  stocking_model:"Xác minh mật độ thả phù hợp với đúng hệ thống và điều kiện nước.",production_volume:"Bổ sung benchmark năng suất/sản lượng cho đúng hệ thống.",
  water_source:"Xác minh nguồn cấp, đường cấp–thoát và chất lượng nước thực tế.",no_commercial_feed_pathway:"Chứng minh khẩu phần không dùng thức ăn công nghiệp vẫn đủ dinh dưỡng cho cả vụ.",
  no_chemical_protocol:"Xây và kiểm chứng quy trình vận hành không hoá chất cho toàn chu kỳ.",renewable_energy_design:"Tính tải điện thực tế rồi mới chốt công suất điện mặt trời, lưu trữ và nguồn dự phòng.",
  culture_method:"Chốt hệ thống nuôi có bằng chứng phù hợp.",input_strategy:"Chốt chiến lược thức ăn/đầu vào có bằng chứng phù hợp.",legal_status:"Xác minh điều kiện pháp lý áp dụng tại địa phương.",water_environment_compatibility:"Đo và xác minh điều kiện nước thực tế so với yêu cầu của đối tượng nuôi."
 };
 return m[k]||human(u?.resolution||u?.resolution_method||"Bổ sung bằng chứng còn thiếu trước khi chốt.");
}
function cardNext(s){
 const u=(s.material_unknowns||[])[0];
 if(u)return nextAction(u);
 if(!s.capital_required_minor)return "Bổ sung chi phí hiện hành cho đúng phương án.";
 if((s.market_evidence_level||"NONE")==="NONE")return "Xác minh đầu ra và giá áp dụng cho khu vực/vụ dự kiến.";
 return "Kiểm chứng các điều kiện còn lại tại ao trước khi triển khai.";
}
function decisionPortfolio(sc,max=6){
 const viable=sc.filter(s=>s.eligibility!=="INELIGIBLE"), rejected=sc.filter(s=>s.eligibility==="INELIGIBLE"), out=[], seen=new Set();
 for(const s of viable){const key=s.scientific_name||s.species_name;if(!seen.has(key)){out.push(s);seen.add(key);if(out.length>=Math.min(4,max))break}}
 for(const s of viable){if(out.length>=max)break;if(!out.includes(s))out.push(s)}
 for(const s of rejected){if(out.length>=max)break;if(!out.includes(s))out.push(s)}
 return out;
}
function scenarioCard(s,i){
 const support=scenarioSupport(s),ready=scenarioReady(s),cycle=s.production_blueprint?.cycle_duration?.days_reference||s.time_to_cash_days;
 const pref=s.owner_intent_preferred?'<span class="pill info">Bạn đang quan tâm</span>':"";
 const market=label(s.market_evidence_level)+(s.market_freshness==="CURRENT"?" · dữ liệu còn mới":s.market_freshness==="STALE"?" · dữ liệu đã cũ":"");
 return `<article class="scenario-card ${i===0&&s.eligibility!=="INELIGIBLE"?"featured":""}">
  <div class="option-top"><div>${pill(support.label,support.tone)}${pref}</div><span class="option-rank">#${html(s.rank||i+1)}</span></div>
  <h3>${html(s.species_name||s.name)}</h3><div class="system-line">${html(friendlyMethod(s.culture_method))} · ${html(friendlyInput(s.input_strategy))}${cycle?" · "+html(num(cycle))+" ngày":""}</div>
  <div class="decision-reason">${html(cardReason(s))}</div>
  <div class="option-kpis"><div><span>Sản lượng</span><strong>${html(scenarioVolume(s))}</strong></div><div><span>Kinh tế</span><strong>${html(scenarioCost(s))}</strong></div><div><span>Thị trường</span><strong>${html(market)}</strong></div><div><span>Còn phải làm rõ</span><strong>${html(num(s.blocking_unknowns||0))} điểm</strong></div></div>
  <div class="next-step"><b>Bước tiếp theo</b><span>${html(cardNext(s))}</span></div>
  <div class="card-actions"><button class="btn btn-secondary detailScenario" data-i="${i}">Xem phân tích</button>${ready?`<button class="btn btn-primary chooseScenario" data-i="${i}">Chọn & chốt</button>`:""}</div>
 </article>`;
}
'''
s = replace_between(s, "function scenarioStatus(s){", "function friendlyMethod(v){", scenario_block, "scenario cards")

decision_block = r'''async function renderDecision(){
 if(!S.workspace.onboarding.complete){content.innerHTML='<div class="card"><div class="empty"><strong>Cần hoàn tất Vùng nuôi trước</strong><p>BIOS cần điều kiện nền để không đề xuất mù.</p><button id="backFarm" class="btn btn-primary">Thiết lập vùng nuôi</button></div></div>';$("#backFarm").onclick=()=>setSpace("VUNG_NUOI");return}
 const sc=S.workspace.scenarios||[];
 if(!sc.length){content.innerHTML='<div class="grid"><div class="hero-card card span-8"><p class="eyebrow">QUYẾT ĐỊNH TRƯỚC KHI THẢ NUÔI</p><h2>Tìm phương án từ điều kiện thật của vùng nuôi</h2><p>BIOS sẽ dựng nhiều hệ thống nuôi từ điều kiện nước, diện tích, vốn và nguyên tắc của bạn; sau đó mới đối chiếu bằng chứng vận hành và thị trường.</p><div class="hero-actions"><button id="analyze" class="btn btn-secondary">Phân tích phương án</button></div></div><div class="card span-4"><h3>Nguyên tắc</h3><p class="small muted">Không có benchmark phù hợp thì BIOS phải nói rõ là chưa biết, thay vì lấp chỗ trống bằng một con số giả.</p></div></div>';$("#analyze").onclick=analyze;return}
 await fieldEvent("RECOMMENDATION_REVIEWED");
 const shown=decisionPortfolio(sc,6),primary=shown.find(s=>s.eligibility!=="INELIGIBLE")||shown[0];
 const lead=primary?(scenarioReady(primary)?`<strong>Có thể chốt theo bằng chứng hiện tại: ${html(primary.species_name)}</strong><p>${html(cardReason(primary))}</p>`:`<strong>Nên kiểm chứng trước: ${html(primary.species_name)} · ${html(friendlyMethod(primary.culture_method))}</strong><p>${html(cardReason(primary))}</p><div class="lead-next"><b>Thiếu gì để đi tiếp:</b> ${html(cardNext(primary))}</div>`):"";
 const scope=shown.length<sc.length?`Đang hiển thị ${shown.length}/${sc.length} phương án đáng xem nhất, ưu tiên đa dạng đối tượng và hệ thống. Các biến thể yếu hơn vẫn được lưu trong hồ sơ phân tích.`:`Đang hiển thị toàn bộ ${shown.length} phương án hiện có.`;
 content.innerHTML=`<div class="grid">
  <div class="card span-12 decision-lead"><div><p class="eyebrow">KẾT LUẬN HIỆN TẠI</p>${lead}</div><button id="reanalyze" class="btn btn-secondary">Phân tích lại</button></div>
  <div class="card span-12"><div class="card-header"><div><h2>Phương án để ra quyết định</h2><p class="small muted">${html(scope)}</p></div></div><div class="scenario-grid">${shown.map(scenarioCard).join("")}</div>${feedbackBox("RECOMMENDATION_USEFULNESS","Các phương án này có hữu ích cho quyết định của bạn không?")}${feedbackBox("EVIDENCE_TRUST","Cách BIOS thể hiện mức chắc chắn/bằng chứng có tạo niềm tin không?")}</div>
  <div class="card span-12 decision-rule"><strong>Cách đọc kết quả:</strong><span>“Có benchmark” nghĩa là có dữ liệu tham chiếu khớp hơn với hệ thống; không đồng nghĩa với chi phí hiện hành, đầu ra chắc chắn hoặc ao của bạn đã sẵn sàng thả.</span></div>
 </div>`;
 $("#reanalyze").onclick=analyze;document.querySelectorAll(".detailScenario").forEach(b=>b.onclick=()=>showScenario(shown[Number(b.dataset.i)]));document.querySelectorAll(".chooseScenario").forEach(b=>b.onclick=()=>chooseScenario(shown[Number(b.dataset.i)]));bindFeedback()
}
'''
s = replace_between(s, "async function renderDecision(){", "function humanGate(g){", decision_block, "decision render")

scenario_modal = r'''function traceImpact(semantic){return ({
 "CD-01":"Khoanh vùng bằng chứng pháp lý, thị trường và kinh tế theo địa điểm.","CD-02":"Dùng diện tích để quy đổi mật độ/năng suất tham chiếu thành quy mô của ao.","CD-03":"Xác định cách quản lý nguồn cấp–thoát nước.","CD-04":"Là hard gate sinh học trước khi xếp hạng.","CD-05":"Giới hạn knowledge theo mục tiêu sản xuất.","CD-06":"Chỉ là ưu tiên mềm; không được lấn át tính khả thi.","CD-07":"Là trần vốn; chỉ PASS khi có chi phí hiện hành đủ bằng chứng.","CD-08":"Ràng buộc các nguyên tắc bắt buộc của chủ trại.","SYNTHESIS":"Ghép đối tượng + hệ thống + chiến lược đầu vào thành một phương án cụ thể."
 }[semantic]||"Được dùng trong quá trình tổng hợp phương án.")}
function showScenario(s){
 const gates=(s.gate_results||[]).map(humanGate),p=s.production_blueprint||{},e=s.economics||{},m=s.market||{},r=s.risks||{},trace=s.decision_trace||[],unknowns=s.material_unknowns||[];
 const support=scenarioSupport(s),cycle=p.cycle_duration?.days_reference||e.time_to_cash_days;
 const list=(xs,empty,mapper=human)=>xs?.length?'<ul class="clean-list">'+xs.map(x=>'<li>'+html(mapper(x))+'</li>').join("")+'</ul>':'<p class="muted small">'+html(empty)+'</p>';
 const infrastructure=list(p.infrastructure_requirements,'Chưa xác định đủ hạ tầng vật lý.',x=>human(typeof x==="string"?x:(x.effect||x.resolution||"")));
 const waterReq=list(p.environment_strategy?.requirements,'Chưa xác định đủ yêu cầu quản lý nguồn nước.',x=>human(typeof x==="string"?x:(x.effect||x.resolution||"")));
 const hist=e.historical_cycle_cost_reference_minor_range,refYear=hist?.reference_year||e.reference_context?.reference_year;
 const histText=hist?money(hist.min)+'–'+money(hist.max)+(refYear?' · dữ liệu '+refYear:''):'Chưa có benchmark phù hợp';
 const currentCap=e.capital_required_minor?money(e.capital_required_minor):'Chưa có dữ liệu chi phí hiện hành';
 const revenue=e.revenue_reference_minor_range?money(e.revenue_reference_minor_range.min)+'–'+money(e.revenue_reference_minor_range.max):'Chưa có doanh thu tham chiếu phù hợp';
 const actions=[...new Set((unknowns||[]).map(nextAction))];if(!actions.length&&!scenarioReady(s))actions.push(cardNext(s));
 const riskNames={biological:"Sinh học",disease_health:"Sức khoẻ/dịch bệnh",environmental:"Môi trường",infrastructure:"Hạ tầng",operational_complexity:"Độ phức tạp vận hành",input_dependence:"Phụ thuộc đầu vào",financial_downside:"Rủi ro tài chính",market_liquidity:"Thanh khoản đầu ra",evidence_uncertainty:"Độ chắc của bằng chứng"};
 const riskHtml=Object.entries(r).filter(([,v])=>v!=null).map(([k,v])=>summaryTile(riskNames[k]||human(k),human(v))).join("");
 const gateHtml=gates.map(g=>`<div class="gate-row"><div><strong>${html(g.title)}</strong><span>${html(g.text)}</span></div>${pill(g.status,g.status==="PASS"?"ok":g.status==="FAIL"?"bad":"warn")}</div>`).join("");
 const traceHtml=trace.map(x=>`<div class="trace-row"><b>${html(x.semantic)}</b><span>${html(traceImpact(x.semantic))}</span></div>`).join("");
 openModal(modalHead(s.species_name||s.name)+`<div class="scenario-verdict ${support.tone}"><span>${html(support.label)}</span><strong>${html(cardReason(s))}</strong></div>
 <section class="dossier-section"><h3>Mô hình đề xuất</h3><div class="quick-actions">${summaryTile("Hệ thống",friendlyMethod(s.culture_method))}${summaryTile("Đầu vào",friendlyInput(s.input_strategy))}${summaryTile("Diện tích dùng để tính",p.area_basis?.usable_area_m2?num(p.area_basis.usable_area_m2)+" m²":"Chưa rõ")}${summaryTile("Chu kỳ tham chiếu",cycle?num(cycle)+" ngày":"Chưa có benchmark")}${summaryTile("Mật độ tham chiếu",p.stocking?.density_per_m2!=null?num(p.stocking.density_per_m2)+" con/m²":"Chưa có benchmark")}${summaryTile("Sản lượng tham chiếu",scenarioVolume(s))}</div></section>
 <section class="dossier-section"><h3>Nước & hạ tầng</h3><div class="two-col"><div class="subpanel"><b>${html(human(p.environment_strategy?.strategy||"SOURCE_UNRESOLVED"))}</b>${waterReq}</div><div class="subpanel"><b>Hạ tầng vật lý cần có</b>${infrastructure}</div></div></section>
 <section class="dossier-section"><h3>Kinh tế</h3><div class="quick-actions">${summaryTile("Vốn hiện hành",currentCap)}${summaryTile("Chi phí lịch sử tham chiếu",histText)}${summaryTile("Sản lượng tham chiếu",scenarioVolume(s))}${summaryTile("Doanh thu tham chiếu",revenue)}${summaryTile("Thời gian tới thu",e.time_to_cash_days?num(e.time_to_cash_days)+" ngày":"Chưa có benchmark")}</div><p class="evidence-note">Chi phí lịch sử chỉ dùng để hiểu quy mô; không được coi là số vốn hiện hành. BIOS chỉ PASS trần vốn khi có mô hình chi phí hiện hành đủ bằng chứng.</p></section>
 <section class="dossier-section"><h3>Thị trường</h3><div class="quick-actions">${summaryTile("Mức bằng chứng",label(m.evidence_level))}${summaryTile("Độ mới",label(m.market_freshness))}${summaryTile("Tập trung người mua",human(m.buyer_concentration_risk))}</div></section>
 <section class="dossier-section"><h3>Hard gates</h3><div class="gate-list">${gateHtml||'<p class="muted">Chưa có kết quả kiểm tra.</p>'}</div></section>
 <section class="dossier-section action-section"><h3>Cần làm gì tiếp theo</h3>${actions.length?'<ol>'+actions.map(x=>'<li>'+html(x)+'</li>').join('')+'</ol>':'<p>Không còn điểm chặn theo bằng chứng hiện tại.</p>'}</section>
 <section class="dossier-section"><h3>Rủi ro</h3><div class="quick-actions">${riskHtml}</div></section>
 <details class="trace-details"><summary>Step 1 đã ảnh hưởng phương án này thế nào?</summary><div class="trace-list">${traceHtml||'<p class="muted">Thiếu reasoning trace — đây là lỗi cần điều tra.</p>'}</div></details>`);
}

'''
s = replace_between(s, "function showScenario(s){", "async function chooseScenario(s){", scenario_modal, "scenario dossier")
p.write_text(s, encoding="utf-8")


# Responsive, decision-first visual hierarchy.
p, css = read("bios_runtime/domains/aquaculture/web/styles.css")
css += r'''

/* Decision product v3: evidence-first, farmer-readable comparison */
.decision-lead{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;background:linear-gradient(135deg,#f2f8f5,#fff);border-color:#cfe0d8}.decision-lead strong{display:block;font-size:20px;letter-spacing:-.02em}.decision-lead p{margin:6px 0 0;color:var(--muted);max-width:850px}.lead-next{margin-top:10px;font-size:13px}.decision-rule{display:flex;gap:8px;align-items:flex-start;font-size:13px}.decision-rule span{color:var(--muted)}
.scenario-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.scenario-card{min-height:0;padding:20px}.scenario-card.featured{border-color:#78aa9a;box-shadow:0 0 0 2px #e7f2ee}.option-top{display:flex;justify-content:space-between;gap:8px;align-items:flex-start}.option-top>div{display:flex;gap:6px;flex-wrap:wrap}.option-rank{font-size:11px;color:var(--muted);font-weight:800}.scenario-card h3{font-size:22px;margin:12px 0 3px}.system-line{font-size:13px;color:#4d5b56;font-weight:650}.decision-reason{margin:14px 0;padding:11px 12px;border-radius:12px;background:#f4f8f6;color:#33443e;font-size:13px}.option-kpis{display:grid;grid-template-columns:1fr 1fr;border:1px solid var(--line);border-radius:13px;overflow:hidden}.option-kpis>div{padding:11px 12px;min-width:0}.option-kpis>div:nth-child(odd){border-right:1px solid var(--line)}.option-kpis>div:nth-child(-n+2){border-bottom:1px solid var(--line)}.option-kpis span,.option-kpis strong{display:block}.option-kpis span{font-size:10px;letter-spacing:.04em;text-transform:uppercase;color:var(--muted);font-weight:750}.option-kpis strong{font-size:12px;margin-top:4px;overflow-wrap:anywhere}.next-step{display:flex;flex-direction:column;gap:3px;margin:13px 0;padding-left:11px;border-left:3px solid #9dbdaf}.next-step b{font-size:11px;text-transform:uppercase;color:var(--brand)}.next-step span{font-size:12px;color:var(--muted)}
.dossier-section{padding:18px 0;border-top:1px solid var(--line)}.dossier-section:first-of-type{border-top:0}.dossier-section h3{font-size:17px;margin:0 0 11px}.scenario-verdict{display:flex;flex-direction:column;gap:5px;padding:14px;border-radius:14px;margin-bottom:4px}.scenario-verdict span{font-size:11px;font-weight:800;text-transform:uppercase}.scenario-verdict strong{font-size:14px}.scenario-verdict.ok{background:#eaf6ef;color:var(--ok)}.scenario-verdict.warn{background:#fff7e8;color:#7d5b17}.scenario-verdict.bad{background:#fbeceb;color:var(--bad)}.scenario-verdict.info{background:#edf4f7;color:var(--info)}.two-col{display:grid;grid-template-columns:1fr 1fr;gap:10px}.subpanel{border:1px solid var(--line);border-radius:13px;padding:13px;background:#fbfcfb}.subpanel>b{display:block;margin-bottom:7px}.clean-list{margin:7px 0 0;padding-left:19px}.clean-list li{margin:5px 0;color:#4e5c57;font-size:13px}.evidence-note{font-size:12px;color:var(--muted);margin:10px 2px 0}.gate-list{display:flex;flex-direction:column;gap:8px}.gate-row{display:flex;justify-content:space-between;gap:10px;align-items:flex-start;border:1px solid var(--line);border-radius:12px;padding:11px}.gate-row strong,.gate-row span{display:block}.gate-row span{font-size:12px;color:var(--muted);margin-top:2px}.action-section{background:#f3f8f5;border-radius:14px;padding:16px;margin:4px 0}.action-section ol{margin:0;padding-left:20px}.action-section li{margin:7px 0}.trace-details{border-top:1px solid var(--line);padding:16px 0 4px}.trace-details summary{cursor:pointer;font-weight:750;color:var(--brand)}.trace-list{margin-top:10px}.trace-row{display:grid;grid-template-columns:70px 1fr;gap:10px;padding:8px 0;border-bottom:1px dashed var(--line)}.trace-row span{font-size:12px;color:var(--muted)}
dialog{max-height:90vh;overflow:auto}
@media(max-width:760px){.scenario-grid{grid-template-columns:1fr}.decision-lead{display:block}.decision-lead .btn{margin-top:14px;width:100%}.decision-rule{display:block}.option-kpis{grid-template-columns:1fr}.option-kpis>div:nth-child(odd){border-right:0}.option-kpis>div{border-bottom:1px solid var(--line)}.option-kpis>div:last-child{border-bottom:0}.two-col{grid-template-columns:1fr}.quick-actions{grid-template-columns:1fr 1fr}dialog{width:100vw;max-width:none;max-height:92dvh;border-radius:24px 24px 0 0;margin:auto 0 0;position:fixed;left:0;right:0;bottom:0}.modal-wrap{padding:18px 16px calc(28px + var(--safe))}.modal-head{position:sticky;top:-18px;background:#fff;z-index:2;padding:12px 0 8px;margin-bottom:8px}.scenario-card .card-actions{display:grid;grid-template-columns:1fr}.scenario-card .card-actions .btn{width:100%}}
'''
p.write_text(css, encoding="utf-8")


# Regression coverage for exactly the reported defects.
test = root / "tests/test_aquaculture_decision_product_v3.py"
test.write_text(r'''from __future__ import annotations
import unittest
from pathlib import Path
from bios_runtime.domains.aquaculture.synthesis import synthesize_candidate

ROOT=Path(__file__).resolve().parents[1]

class AquacultureDecisionProductV3Test(unittest.TestCase):
    def test_water_requirements_are_not_duplicated_as_infrastructure(self):
        discovery={
            "CD-01":{"normalized_value":{"label":"Đông Hải, Bạc Liêu","country":"VN"}},
            "CD-02":{"normalized_value":{"value":650,"unit":"m2"}},
            "CD-03":{"normalized_value":"RIVER"},"CD-04":{"normalized_value":"BRACKISH"},
            "CD-05":{"normalized_value":"GROW_OUT"},"CD-06":{"normalized_value":"Cá nâu"},
            "CD-07":{"normalized_value":{"amount_minor":"50000000","currency":"VND"}},
            "CD-08":{"normalized_value":"NONE_STATED"},
        }
        c=synthesize_candidate(species="Scatophagus argus",culture_method="POND",input_strategy="COMMERCIAL_FEED",discovery=discovery,farm_location={"country":"VN","province":"Bạc Liêu"},economics={},preferred_species="Scatophagus argus",owner_profile={},knowledge_refs=[])
        prod=c["production"]
        self.assertIn("verify current source-water condition before stocking",prod["environment_strategy"]["requirements"])
        self.assertNotIn("verify current source-water condition before stocking",prod["infrastructure_requirements"])
        self.assertIn("culture pond",prod["infrastructure_requirements"])

    def test_farmer_ui_has_diversified_portfolio_and_human_dossier(self):
        js=(ROOT/"bios_runtime/domains/aquaculture/web/app.js").read_text()
        css=(ROOT/"bios_runtime/domains/aquaculture/web/styles.css").read_text()
        for marker in ["function decisionPortfolio","Nguồn nước mở có kiểm soát","Nên kiểm chứng trước","Cần làm gì tiếp theo","Chi phí lịch sử chỉ dùng để hiểu quy mô","Step 1 đã ảnh hưởng phương án này thế nào?"]:
            self.assertIn(marker,js)
        self.assertIn("Decision product v3",css)
        self.assertNotIn('html(p.environment_strategy?.strategy||"Chưa chốt")',js)

if __name__=="__main__": unittest.main()
''', encoding="utf-8")

print("AQ_DECISION_PRODUCT_V3_PATCH=APPLIED")
