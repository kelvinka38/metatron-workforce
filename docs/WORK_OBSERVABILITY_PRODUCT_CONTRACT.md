# METATRON WORKFORCE — WORK OBSERVABILITY PRODUCT CONTRACT

**Status:** APPROVED FOR IMPLEMENTATION  
**Scope:** Human-facing Work Orders, Objective monitoring, live Work Graph projection, execution proof, Dashboard, Chat/Telegram monitoring  
**Authority:** Workforce canonical management/autonomy contracts + BIOS Product #1 Trust Surface

## Product promise

When Metatron accepts work, the Human must receive an accountable Work Order rather than an engineering receipt or a prose explanation. The Human must be able to inspect what the task is, who owns and performs it, the reporting relationship, the actual Work breakdown and dependencies, Definition of Done, workload, committed ETA when one exists, risks/blockers, current progress, and evidence supporting execution/completion.

## Core invariants

```text
NO MANAGEMENT CLAIM WITHOUT MANAGEMENT EVIDENCE
NO ASSIGNMENT CLAIM WITHOUT ASSIGNMENT EVIDENCE
NO EXECUTION CLAIM WITHOUT EXECUTION EVIDENCE
NO ETA CLAIM WITHOUT A CANONICAL SCHEDULE / COMMITMENT
NO COMPLETION CLAIM WITHOUT OUTCOME / ACCEPTANCE EVIDENCE
```

A UI may never independently invent lifecycle state, workers, percentages, ETA, risk, or completion. Dashboard, chat monitoring and Telegram Work Cards are projections of the same durable Workforce management state. Missing institutional data must be rendered explicitly as `UNASSIGNED`, `NOT DEFINED`, `NOT COMMITTED`, or the equivalent; it must not be cosmetically filled by an LLM.

## Canonical projection

```text
Management Objective
+ Autonomous Objective Work
+ Work Graph
+ Management Lease
+ Management Events
+ Assignment references
+ Scheduling / commitment evidence
+ Evidence references
→ Work Order / Work Observability View
→ Dashboard / Chat Monitor / Telegram Work Card
```

## Required Work Order fields

Every accepted Objective monitoring surface must expose, at the appropriate abstraction level:

- Task / Objective summary;
- lifecycle status;
- progress derived from completed Work steps / total Work steps;
- Objective owner;
- Human / institutional reporting target;
- total workload as actual planned Work items;
- committed ETA/deadline only when canonical scheduling evidence exists;
- staffing state and assignment evidence;
- Work items and dependencies;
- required role/capability for each Work item;
- actual performer/Worker when assigned;
- Definition of Done / acceptance criteria;
- current note, risk, blocker, recovery state;
- last material activity timestamp;
- execution proof state;
- evidence count and references at terminal state.

Internal case/objective/queue identifiers belong in Details/Evidence, not the primary Human summary.

## Work Breakdown truth rule

The Work table is a projection of the canonical Work Graph. It is forbidden to generate a decorative checklist merely for presentation. A Work item may display an owner only when assignment evidence exists. A Work item may display Done only when its acceptance/evidence requirements have been satisfied by canonical execution state.

## Execution-proof rule

`EXECUTING` may be presented as actively running only when durable management/execution evidence supports it and recent material activity is observable. Otherwise the Human projection must distinguish queued/ready/stale/waiting state rather than pretending activity.

## Dashboard

The Workforce service must expose a live Dashboard that:

1. lists Objectives and progress;
2. provides board/status grouping for planned, executing, waiting/blocked and terminal Work;
3. opens a single Objective detail view;
4. refreshes from the canonical observability API without page reload;
5. shows Work Order fields, Work Graph rows, dependencies, role/capability, actual staffing evidence, DoD, evidence and last activity;
6. makes blockers, unstaffed Work, missing ETA commitments and stale/unproven execution visible;
7. never turns absence of execution evidence into a green `Working` state.

## Chat / Telegram Task Monitoring

After Objective acceptance the primary conversation must provide a Human-readable Work Order and a `Monitor task` affordance. The Work Order must be updated from canonical observability state. Normal progress updates the same monitoring surface rather than spamming the conversation. New messages are reserved for material blocker/approval/terminal events.

The assistant behavior for an execution request is `create/own/manage Work`, not `explain what a task board should contain`.

## Completion UX

Terminal completion must show at least Objective outcome status, completed Work / total Work, accountable owner/performers, DoD satisfaction, evidence references, and production/deployment/test evidence where the Objective requires them.

## Acceptance

This product slice is accepted only when a real production Objective can be observed from acceptance through planning, staffing, execution, verification and terminal state; Dashboard and chat/Telegram projections must match durable management evidence at every sampled state. A synthetic UI-only state transition is not acceptance.
