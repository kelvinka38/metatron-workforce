# METATRON WORKFORCE — WORK OBSERVABILITY PRODUCT CONTRACT

**Status:** APPROVED FOR IMPLEMENTATION  
**Scope:** Human-facing Objective monitoring, live Work Graph projection, execution proof, Dashboard, Chat/Telegram monitoring  
**Authority:** Workforce canonical management/autonomy contracts + BIOS Product #1 Trust Surface

## Product promise

When Metatron says it is doing work, the Human must be able to inspect what Objective exists, what Work was planned, current progress, who/what capability owns the work, blockers/recovery, and the evidence supporting execution/completion.

## Core invariant

```text
NO EXECUTION CLAIM WITHOUT EXECUTION EVIDENCE
NO COMPLETION CLAIM WITHOUT OUTCOME / ACCEPTANCE EVIDENCE
```

A UI may never independently invent lifecycle state. Dashboard, chat monitoring and Telegram Work Cards are projections of the same durable Workforce management state.

## Canonical projection

```text
Management Objective
+ Autonomous Objective Work
+ Work Graph
+ Management Lease
+ Management Events
+ Assignment references
+ Evidence references
→ Work Observability View
→ Dashboard / Chat Monitor / Telegram Work Card
```

## Required Human-facing fields

- Objective summary
- lifecycle status
- progress derived from completed Work steps / total Work steps
- current Work items and dependencies
- required capability / assignment evidence where known
- owner Worker
- current blocker / recovery state
- last material activity timestamp
- execution proof state
- evidence count and references at terminal state

Internal case/objective/queue identifiers belong in Details/Evidence, not the primary Human summary.

## Execution-proof rule

`EXECUTING` may be presented as actively running only when the durable Work state says EXECUTING and an active management lease exists at observation time. Otherwise the Human projection must distinguish queued/ready/stale/waiting state rather than pretending activity.

## Dashboard

The Workforce service must expose a live Dashboard that:

1. lists Objectives and progress;
2. opens a single Objective detail view;
3. refreshes from the canonical observability API without page reload;
4. shows Work Graph rows, dependencies, capability/owner, evidence and last activity;
5. makes blockers and stale execution visible.

## Chat / Telegram Task Monitoring

After Objective acceptance the primary conversation should provide a Human-readable Work Card and a `Monitor task` affordance. The Work Card should be updated from the canonical observability view. Normal progress should update the monitoring surface rather than spam the conversation. New messages are reserved for material blocker/approval/terminal events.

## Completion UX

Terminal completion must show at least:

- Objective outcome status;
- completed Work / total Work;
- owner;
- evidence references;
- production/deployment/test evidence where the Objective requires them.

## Acceptance

This product slice is accepted only when a real production Objective can be observed from acceptance through planning/execution/verification/terminal state and the Dashboard/monitor projection matches durable management evidence at every sampled state.
