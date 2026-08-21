# METATRON WORKFORCE — PHASE 3 IMPLEMENTATION CONTRACT

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 3 — Workplace / Communication
**Depends on:** Phase 2 G2 PASS
**Status:** IMPLEMENTATION BASELINE

## 1. Purpose

This artifact is the single implementation contract for Phase 3. It prevents implementation scope from being scattered across ad-hoc documents.

Phase 3 must establish the institutional workplace through which Humans and Workers communicate and coordinate work.

## 2. Canonical implementation surface

The Phase 3 implementation surface is limited to:

1. workplace communication;
2. conversations;
3. meetings;
4. institutional work queue;
5. workplace authorization boundary;
6. attributable communication context.

No new institutional domain is introduced by this phase.

## 3. Required use cases

### UC-01 Human → Head

A Human can initiate a conversation with the Head Worker when authorized.

Required evidence:

- initiating actor;
- recipient Worker;
- organizational context;
- authorization context;
- message identity;
- creation time;
- attributable message history.

### UC-02 Human → authorized Worker

A Human can communicate with an authorized subordinate or other authorized Worker.

The implementation MUST reject unauthorized access rather than silently creating communication access.

### UC-03 Worker → Human

A Worker can return an attributable response to a Human within an authorized conversation.

### UC-04 Worker → Worker

Workers can communicate with other authorized Workers while preserving organizational and authorization context.

### UC-05 Meeting

A Human can create/request a meeting, participants can join where authorized, and the meeting can preserve agenda, discussion, decisions, action items, owners, deadlines, minutes, evidence references, and follow-up state.

A meeting does not itself create authority.

### UC-06 Institutional queue

A Worker or Human can receive and track:

- requests;
- assignment references;
- approval requests;
- review requests;
- reports;
- escalations;
- meeting invitations;
- proposals;
- notifications.

Queue state must remain separate from the authoritative lifecycle of the referenced object.

### UC-07 Outstanding work

The workplace must distinguish at minimum:

- pending;
- due;
- overdue;
- blocked;
- awaiting approval;
- awaiting review;
- escalated;
- completed/resolved.

## 4. Mandatory invariants

The implementation MUST preserve:

1. Worker ≠ Conversation.
2. Conversation ≠ Work.
3. Conversation ≠ Assignment.
4. Conversation ≠ Authorization.
5. Meeting ≠ Decision.
6. Meeting ≠ Assignment.
7. Queue item ≠ Work.
8. Queue item ≠ Authorization.
9. Communication does not create authority.
10. Communication does not create execution.
11. Participant access does not create institutional authority.
12. Queue state does not silently mutate authoritative domain state.
13. Historical communication remains attributable.
14. Cross-domain references do not transfer ownership.

## 5. Authorization contract

Every restricted workplace action must be evaluated against the applicable authorization context.

Conceptually:

```text
ACTOR
  ↓
PARTICIPATION
  ↓
ORGANIZATION CONTEXT
  ↓
ROLE / POSITION
  ↓
WORKPLACE CONTEXT
  ↓
REQUEST / MESSAGE / MEETING / QUEUE ACTION
  ↓
AUTHORIZATION CONTEXT
```

The workplace may coordinate an authorization result but must not manufacture Governance or Gateway authority.

## 6. Attribution contract

Every material workplace action must preserve:

```text
WHO
WHAT
WHEN
WHY / CONTEXT
UNDER WHICH AUTHORIZATION
REFERENCING WHICH INSTITUTIONAL OBJECT
```

Creation time and actual occurrence time must remain distinguishable where materially relevant.

## 7. Acceptance evidence

Phase 3 cannot be marked PASS until evidence demonstrates:

- Human → Head communication;
- Human → authorized subordinate communication;
- Worker → Human response;
- Worker → Worker communication;
- conversation visibility/participation control;
- meeting creation/request;
- meeting participation;
- work issuance/request through the appropriate workflow;
- proposal review;
- report receipt;
- institutional queue operation;
- outstanding-work visibility;
- attribution preservation;
- authorization boundary preservation;
- queue-state isolation from authoritative domain state.

## 8. Scope control

Anything discovered during implementation belongs to exactly one category:

```text
PHASE 3
FUTURE PHASE
EXTERNAL DOMAIN
OUT OF SCOPE
```

It must not be added to Phase 3 merely because it is convenient to implement alongside workplace functionality.

## 9. Repository structure rule

Phase 3 implementation work must remain under the existing repository structure. Do not create parallel `phase-3-*` repositories, duplicate Phase 3 document trees, or temporary artifact directories.

The existing `docs/PHASE_3/` directory remains the canonical Phase 3 documentation surface.

## 10. Gate rule

`PHASE_3_GATE.md` remains `PENDING` until the acceptance evidence exists.

Only after evidence is complete may the gate be changed to PASS and Phase 4 begin.
