# G12 — Fresh Certification Trigger

## Purpose

This marker exists to trigger the authoritative G12 production-readiness workflow on `main` after the runtime implementation was declared closed and certification remained pending.

## Certification rule

This file does **not** assert PASS. The authoritative result is the GitHub Actions `G12 Production Readiness Evidence` run for the commit containing this marker.

Required outcome:

```text
FRESH CI
→ FULL ACCEPTANCE
→ DURABLE RECOVERY ACCEPTANCE
→ G12 ACCEPTANCE
→ DEPLOYABLE RUNTIME SMOKE
→ STATE / LOGS / METRICS / TRACES / VISIBILITY EVIDENCE
→ CLEAN WORKTREE / EXACT COMMIT
→ G12 PASS
```

If any step fails, the failure is the next implementation target and the runtime remains uncertified.
