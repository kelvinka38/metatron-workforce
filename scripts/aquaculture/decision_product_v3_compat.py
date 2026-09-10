#!/usr/bin/env python3
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
p=root/'bios_runtime/domains/aquaculture/web/app.js'
s=p.read_text(encoding='utf-8')
old='''function scenarioCard(s,i){\n const support=scenarioSupport(s),ready=scenarioReady(s),cycle=s.production_blueprint?.cycle_duration?.days_reference||s.time_to_cash_days;'''
new='''function scenarioCard(s,i){\n const ready=s.eligibility==="ELIGIBLE"&&Number(s.blocking_unknowns||0)===0&&s.recommendation_state==="FINAL_FOR_CURRENT_EVIDENCE";\n const support=scenarioSupport(s),cycle=s.production_blueprint?.cycle_duration?.days_reference||s.time_to_cash_days;'''
if old not in s:
    raise SystemExit('selection-contract anchor missing')
s=s.replace(old,new,1)
old2='''async function chooseScenario(s){if(s.recommendation_state!=="FINAL_FOR_CURRENT_EVIDENCE"){openModal(modalHead("Chưa thể chốt phương án")+'''
new2='''const finalEvidenceGateLabel="CHƯA ĐỦ BẰNG CHỨNG ĐỂ KHUYẾN NGHỊ / CHỐT";\nasync function chooseScenario(s){if(s.recommendation_state!=="FINAL_FOR_CURRENT_EVIDENCE"){openModal(modalHead(finalEvidenceGateLabel)+'''
if old2 not in s:
    raise SystemExit('evidence-gate anchor missing')
s=s.replace(old2,new2,1)
p.write_text(s,encoding='utf-8')
print('AQ_PRODUCT_V3_SELECTION_CONTRACT=PASS')
print('AQ_PRODUCT_V3_EVIDENCE_GATE=PASS')
