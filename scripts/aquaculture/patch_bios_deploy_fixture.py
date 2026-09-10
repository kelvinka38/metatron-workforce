#!/usr/bin/env python3
from pathlib import Path
import sys

p=Path(sys.argv[1] if len(sys.argv)>1 else 'scripts/highway-tasks/bios-deploy.sh')
s=p.read_text(encoding='utf-8')
anchor='''generated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})'''
if 'AQ_ACCEPTANCE_ISOLATED_FARM' in s:
    print('AQ_ACCEPTANCE_ISOLATED_FARM=ALREADY_PATCHED')
    raise SystemExit(0)
if anchor not in s:
    raise SystemExit('aquaculture live fixture anchor not found')
setup='''# Never run acceptance against a mutable farmer pilot. Create a fresh farm inside\n# the synthetic acceptance Case and seed the exact reality this test asserts.\nfixture=post("/farms/create",{\n    "case_id":case_id,\n    "name":"BIOS Aquaculture deploy acceptance",\n    "location":{"country":"VN","province":"Bạc Liêu"}\n})\nfarm_id=fixture["farm_id"]\npost("/discovery/update",{\n    "case_id":case_id,\n    "farm_id":farm_id,\n    "items":[\n      {"semantic_key":"CD-01","original_value":"Đông Hải, Bạc Liêu","normalized_value":{"label":"Đông Hải, Bạc Liêu","country":"VN","province":"Bạc Liêu"},"field_state":"DECLARED"},\n      {"semantic_key":"CD-02","original_value":"650 m2","normalized_value":{"value":650,"unit":"m2"},"field_state":"DECLARED"},\n      {"semantic_key":"CD-03","original_value":"Sông","normalized_value":"RIVER","field_state":"DECLARED"},\n      {"semantic_key":"CD-04","original_value":"Nước lợ","normalized_value":"BRACKISH","field_state":"DECLARED"},\n      {"semantic_key":"CD-05","original_value":"Nuôi thương phẩm","normalized_value":"GROW_OUT","field_state":"DECLARED"},\n      {"semantic_key":"CD-06","original_value":"Cá nâu","normalized_value":"Scatophagus argus","field_state":"DECLARED","owner_lock":False},\n      {"semantic_key":"CD-07","original_value":"500000000 VND","normalized_value":{"amount_minor":"500000000","currency":"VND"},"field_state":"DECLARED","owner_lock":True},\n      {"semantic_key":"CD-08","original_value":"Không thức ăn công nghiệp; không hóa chất; năng lượng tái tạo","normalized_value":"Không thức ăn công nghiệp; không hóa chất; năng lượng tái tạo","field_state":"DECLARED","owner_lock":True}\n    ]\n})\nprint("AQ_ACCEPTANCE_ISOLATED_FARM=PASS",farm_id)\ngenerated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})'''
p.write_text(s.replace(anchor,setup,1),encoding='utf-8')
print('AQ_BIOS_DEPLOY_FIXTURE_PATCH=PASS')
