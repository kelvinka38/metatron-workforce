#!/usr/bin/env python3
import os,sys,time
seconds=float(sys.argv[1])
print(f"FABRIC_PROBE_START task={os.environ.get('HIGHWAY_TASK_ID')} at={time.time()}",flush=True)
time.sleep(seconds)
print(f"FABRIC_PROBE_END task={os.environ.get('HIGHWAY_TASK_ID')} at={time.time()}",flush=True)
