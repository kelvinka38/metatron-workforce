# METATRON WORKFORCE — PHASE 3 / 03 MEETING MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Depends on:** Phase 2 canonical entities, state, transition, provenance and attribution models

## 1. Purpose

Define meetings as an institutional coordination construct supporting participants, agenda, purpose, discussion, decisions, action items, owners, deadlines, minutes, evidence, and follow-up.

## 2. Meeting construct

A meeting MUST preserve:

- meeting identity;
- organizer;
- participants;
- organization context;
- purpose;
- agenda;
- scheduled start/end;
- actual start/end where held;
- discussion references;
- decisions;
- action items;
- owners;
- deadlines;
- minutes;
- evidence references;
- follow-up state.

## 3. Lifecycle

```text
PLANNED
  ↓
SCHEDULED
  ↓
HELD
  ↓
MINUTES_PENDING
  ↓
CLOSED
```

A meeting may be cancelled before being held. A meeting that was held MUST retain the distinction between scheduled and actual time.

## 4. Decisions

A meeting may produce a decision reference. The meeting itself does not manufacture authority.

```text
Meeting discussion
      ↓
Decision proposal/reference
      ↓
Applicable authority / workflow
      ↓
Decision outcome
```

## 5. Action items

Action items MUST identify, where applicable:

- owner;
- work/reference identity;
- due time;
- originating meeting;
- acceptance criteria;
- status;
- authorization context.

An action item is not automatically an Assignment or authorization. The applicable Work/Assignment lifecycle remains authoritative.

## 6. Evidence and minutes

Minutes MUST be attributable to their author and recording time. Evidence referenced from a meeting remains owned by its authoritative source.

## 7. Coordination requirement

The workplace MUST support cross-functional meetings such as:

```text
Head Workforce
Head Tech
Head People
Head Economy
        ↓
Strategic meeting
        ↓
Decisions / action references / follow-up
```

The organizational and authorization boundaries remain intact.

## 8. Invariants

1. Meeting ≠ Conversation, although a meeting may have associated communication.
2. Meeting ≠ Decision.
3. Meeting ≠ Assignment.
4. Meeting ≠ Execution.
5. Scheduled time ≠ actual time.
6. Discussion does not itself create authority.
