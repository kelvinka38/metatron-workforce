from __future__ import annotations

from pathlib import Path
import sys


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_aq_v4_deploy.py INPUT OUTPUT")
    source = Path(sys.argv[1])
    target = Path(sys.argv[2])
    text = source.read_text()

    old_cd8 = '{"semantic_key":"CD-08","original_value":"Không thức ăn công nghiệp; không hóa chất; năng lượng tái tạo","normalized_value":"Không thức ăn công nghiệp; không hóa chất; năng lượng tái tạo","field_state":"DECLARED","owner_lock":True}'
    new_cd8 = '{"semantic_key":"CD-08","original_value":"","normalized_value":"NONE_STATED","field_state":"DECLARED","owner_lock":True}'
    if old_cd8 in text:
        text = text.replace(old_cd8, new_cd8)

    start = 'generated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})'
    end = 'market=post("/farmer/market",{"case_id":case_id,"farm_id":farm_id})'
    a = text.index(start)
    b = text.index(end, a)
    replacement = '''generated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})
assert generated["candidate_count"]==3, generated
assert generated["reasoning_version"]=="aq-product-reasoning-4.0", generated
scenarios=post("/scenarios/list",{"case_id":case_id,"farm_id":farm_id})
assert len(scenarios)==3, scenarios
for sc in scenarios:
    post("/scenarios/evaluate",{"case_id":case_id,"scenario_id":sc["scenario_id"],"version":sc["version"]})
rec=post("/decision/recommend",{"case_id":case_id,"farm_id":farm_id})
assert rec["comparator_version"]=="aq-decision-comparator-1.2", rec
ws=post("/farmer/workspace",{"case_id":case_id,"farm_id":farm_id})
assert ws["reasoning_version"]=="aq-product-reasoning-4.0", ws
assert ws["proposal_count"]==3, ws
assert len(ws["scenarios"])==3, ws["scenarios"]
assert len(ws["unknowns"])<=8, ws["unknowns"]
props=[x["property"] for x in ws["unknowns"]]
assert len(props)==len(set(props)), props
farmer_resolution=" ".join(x.get("resolution_method","") for x in ws["unknowns"]).lower()
assert "obtain validated" not in farmer_resolution, farmer_resolution
assert "complete economic model" not in farmer_resolution, farmer_resolution
primary=next(x for x in ws["scenarios"] if x.get("proposal_role")=="PRIMARY")
assert primary["scientific_name"]=="Scatophagus argus", primary
trace={x.get("semantic") for x in primary.get("decision_trace") or []}
assert {"CD-01","CD-02","CD-03","CD-04","CD-05","CD-06","CD-07","CD-08"}.issubset(trace), trace
blueprint=primary.get("production_blueprint") or {}
assert (blueprint.get("area_basis") or {}).get("usable_area_m2")==650.0, blueprint
assert (blueprint.get("environment_strategy") or {}).get("water_source")=="RIVER", blueprint
integrated=[x for x in ws["scenarios"] if x.get("species_configuration")=="POLYCULTURE"]
assert integrated, ws["scenarios"]
assert any(" + " in x.get("species_name","") for x in integrated), integrated
assert all(x["eligibility"]=="CONDITIONAL" for x in integrated), integrated
assert all(x["recommendation_state"]=="PROVISIONAL" for x in integrated), integrated
assert all(int(x.get("blocking_unknowns") or 0)>0 for x in integrated), integrated
print("AQ_LIVE_THREE_PROPOSAL_REASONING=PASS",[x.get("species_name") for x in ws["scenarios"]])
print("AQ_LIVE_INTEGRATED_FAIL_CLOSED=PASS",[x.get("species_name") for x in integrated])
print("AQ_LIVE_UNKNOWN_DEDUP=PASS",props)
'''
    text = text[:a] + replacement + text[b:]
    text = text.replace(
        'print("AQ_LIVE_SYSTEM_VARIANTS=PASS",len(scat),sorted(variants))\nprint("AQ_LIVE_ECONOMICS_REFERENCE_GUARD=PASS")\n',
        '',
    )
    target.write_text(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
