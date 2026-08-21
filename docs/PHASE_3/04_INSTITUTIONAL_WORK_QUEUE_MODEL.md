# METATRON WORKFORCE — PHASE 3 / 04 INSTITUTIONAL WORK QUEUE MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`

## 1. Purpose

Define the institutional inbox/work queue required by Phase 3. Workers and Humans need a durable place to receive and track requests, assignments, approvals, reviews, reports, escalations, meeting invitations, proposals, and notifications.

## 2. Queue item classes

The workplace MUST support at least:

- request;
- assignment reference;
- approval request;
- review request;
- report;
- escalation;
- meeting invitation;
- proposal;
- notification.

## 3. Queue item contract

Each material queue item MUST preserve, where applicable:

- queue item identity;
- recipient/owner;
- organization context;
- source actor;
- item type;
- referenced institutional object;
- priority;
- created time;
- due time where applicable;
- status;
- authorization context;
- provenance/attribution references.

## 4. Queue lifecycle

```text
CREATED
  ↓
DELIVERED
  ↓
ACKNOWLEDGED
  ↓
RESOLVED / DISMISSED / ESCALATED
```

The queue state is operational state. It MUST NOT overwrite the authoritative lifecycle of the referenced object.

## 5. Outstanding work

A Worker or Human management interface MUST be able to distinguish at minimum:

- pending;
- due;
- overdue;
- blocked;
- awaiting approval;
- awaiting review;
- escalated;
- completed/resolved.

## 6. Priority and deadlines

Priority and due time are coordination attributes. They do not create authorization or change the authority of the referenced Work, Assignment, Proposal, or Decision.

## 7. Invariants

1. Queue item ≠ Work.
2. Queue item ≠ Assignment.
3. Queue item ≠ Authorization.
4. Queue item ≠ Report.
5. Queue state MUST NOT mutate another object's authoritative state without an explicit contract.
6. Every material queue item remains attributable to its source.
