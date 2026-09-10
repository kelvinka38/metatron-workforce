import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("highwayd",ROOT/"highwayd.py")
highwayd=importlib.util.module_from_spec(spec)
spec.loader.exec_module(highwayd)

SHA="a"*40

class HighwaySchedulerTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        root=Path(self.tmp.name)
        registry={
            "tasks":{
                "read":{"command":["true"],"resources":[{"name":"prod:workforce","mode":"READ"}]},
                "write":{"command":["true"],"resources":[{"name":"prod:workforce","mode":"WRITE"}]},
                "nested-write":{"command":["true"],"resources":[{"name":"prod:workforce:state","mode":"WRITE"}]},
                "other":{"command":["true"],"resources":[{"name":"prod:bios","mode":"WRITE"}]},
            }
        }
        rp=root/"registry.json"
        rp.write_text(json.dumps(registry),encoding="utf-8")
        self.registry=highwayd.Registry(rp)
        self.store=highwayd.HighwayStore(root/"highway.db")

    def tearDown(self):
        self.tmp.cleanup()

    def make(self,kind,**extra):
        p={"kind":kind,"source_sha":SHA}
        p.update(extra)
        return self.store.create_task(p,self.registry)

    def test_readers_share_resource_but_writer_waits(self):
        r1=self.make("read",priority=100)
        r2=self.make("read",priority=99)
        w=self.make("write",priority=90)
        c1=self.store.claim_next("e1")
        c2=self.store.claim_next("e2")
        self.assertEqual(r1["task_id"],c1["task_id"])
        self.assertEqual(r2["task_id"],c2["task_id"])
        self.assertIsNone(self.store.claim_next("e3"))
        self.store.finish(c1["task_id"],"e1",True,0,"","")
        self.assertIsNone(self.store.claim_next("e3"))
        self.store.finish(c2["task_id"],"e2",True,0,"","")
        c3=self.store.claim_next("e3")
        self.assertEqual(w["task_id"],c3["task_id"])

    def test_unrelated_resource_does_not_queue(self):
        w=self.make("write",priority=100)
        other=self.make("other",priority=99)
        c1=self.store.claim_next("e1")
        c2=self.store.claim_next("e2")
        self.assertEqual({w["task_id"],other["task_id"]},{c1["task_id"],c2["task_id"]})

    def test_hierarchical_resource_conflict_is_detected(self):
        parent=self.make("write",priority=100)
        child=self.make("nested-write",priority=90)
        c1=self.store.claim_next("e1")
        self.assertEqual(parent["task_id"],c1["task_id"])
        self.assertIsNone(self.store.claim_next("e2"))

    def test_dependency_prevents_early_execution(self):
        first=self.make("other",priority=50)
        second=self.make("read",priority=100,dependencies=[first["task_id"]])
        c1=self.store.claim_next("e1")
        self.assertEqual(first["task_id"],c1["task_id"])
        self.store.finish(c1["task_id"],"e1",True,0,"","")
        c2=self.store.claim_next("e2")
        self.assertEqual(second["task_id"],c2["task_id"])

    def test_dedupe_key_returns_same_task(self):
        a=self.make("read",dedupe_key="same")
        b=self.make("read",dedupe_key="same")
        self.assertEqual(a["task_id"],b["task_id"])

    def test_resource_helper_allows_read_read_only(self):
        self.assertFalse(highwayd.resource_conflicts({"a":"READ"},{"a":"READ"}))
        self.assertTrue(highwayd.resource_conflicts({"a":"READ"},{"a":"WRITE"}))
        self.assertTrue(highwayd.resource_conflicts({"a:b":"WRITE"},{"a":"READ"}))
        self.assertFalse(highwayd.resource_conflicts({"a":"WRITE"},{"b":"WRITE"}))

    def fabric(self, executors=2):
        root=Path(self.tmp.name)
        fabric=highwayd.HighwayFabric(self.store,self.registry,root,root,executors)
        self.addCleanup(lambda: fabric.pool.shutdown(wait=False, cancel_futures=True))
        return fabric

    def test_explain_reports_dependency_wait(self):
        first=self.make("other",priority=50)
        second=self.make("read",priority=100,dependencies=[first["task_id"]])
        e=self.fabric().explain_task(second["task_id"])
        self.assertEqual("DEPENDENCY_WAIT",e["reason"])
        self.assertEqual(first["task_id"],e["dependency_blockers"][0]["task_id"])
        self.assertEqual("QUEUED",e["dependency_blockers"][0]["state"])

    def test_explain_reports_resource_conflict_with_holder(self):
        writer=self.make("write",priority=100)
        reader=self.make("read",priority=90)
        claimed=self.store.claim_next("writer-executor")
        self.assertEqual(writer["task_id"],claimed["task_id"])
        e=self.fabric().explain_task(reader["task_id"])
        self.assertEqual("RESOURCE_CONFLICT",e["reason"])
        self.assertEqual(writer["task_id"],e["resource_blockers"][0]["holderTaskId"])
        self.assertEqual("WRITE",e["resource_blockers"][0]["holderMode"])

    def test_explain_reports_executor_capacity_only_without_structural_blocker(self):
        task=self.make("other")
        fabric=self.fabric(executors=1)
        with fabric.active_lock:
            fabric.active.add("already-running")
        e=fabric.explain_task(task["task_id"])
        self.assertEqual("EXECUTOR_CAPACITY",e["reason"])
        self.assertEqual(1,e["executor_active"])
        self.assertEqual(0,e["executor_available"])

    def test_explain_reports_schedulable_when_nothing_blocks(self):
        task=self.make("other")
        e=self.fabric(executors=2).explain_task(task["task_id"])
        self.assertEqual("SCHEDULABLE",e["reason"])
        self.assertEqual(2,e["executor_available"])

if __name__=="__main__":
    unittest.main()
