# METATRON WORKFORCE — PHASE 3 / 07 WORKPLACE IMPLEMENTATION ACCEPTANCE CHECKLIST

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 3 — Workplace / Communication
**Implementation contract:** `PHASE_3_IMPLEMENTATION_CONTRACT.md`
**Status:** IMPLEMENTATION CHECKLIST

## 1. Purpose

This is the single acceptance checklist for Phase 3 implementation evidence. It does not create a new domain, replace the Phase 3 implementation contract, or define persistence structure.

## 2. Communication

- [ ] Human → Head Worker communication works.
- [ ] Human → authorized Worker communication works.
- [ ] Worker → Human response works.
- [ ] Worker → Worker communication works.
- [ ] Unauthorized communication access is rejected.
- [ ] Conversation visibility and participation controls are enforced.
- [ ] Communication preserves organizational context.
- [ ] Communication preserves authorization context.
- [ ] Communication preserves attributable message history.

## 3. Meetings

- [ ] Human can create/request a meeting.
- [ ] Authorized participants can join.
- [ ] Unauthorized participants are rejected.
- [ ] Agenda is preserved.
- [ ] Discussion is attributable.
- [ ] Decisions are referenced without making the meeting itself authoritative.
- [ ] Action items preserve owner and deadline.
- [ ] Minutes are attributable.
- [ ] Evidence references are preserved.
- [ ] Follow-up state is preserved.

## 4. Institutional Work Queue

- [ ] Requests can enter the institutional queue.
- [ ] Assignment references can enter the queue.
- [ ] Approval requests can enter the queue.
- [ ] Review requests can enter the queue.
- [ ] Reports can enter the queue.
- [ ] Escalations can enter the queue.
- [ ] Meeting invitations can enter the queue.
- [ ] Proposals can enter the queue.
- [ ] Notifications can enter the queue.
- [ ] Queue state is separate from authoritative referenced-object state.
- [ ] Pending work is visible.
- [ ] Due work is visible.
- [ ] Overdue work is visible.
- [ ] Blocked work is visible.
- [ ] Awaiting-approval work is visible.
- [ ] Awaiting-review work is visible.
- [ ] Escalated work is visible.
- [ ] Completed/resolved work is visible.

## 5. Authorization Boundary

- [ ] Restricted workplace actions evaluate applicable authorization context.
- [ ] Authorization context includes actor.
- [ ] Authorization context includes participation.
- [ ] Authorization context includes organization context.
- [ ] Authorization context includes role/position where applicable.
- [ ] Authorization context includes workplace context.
- [ ] Authorization results are referenced rather than manufactured by Workplace.
- [ ] Governance authority is not created by communication.
- [ ] Gateway enforcement authority is not created by Workplace.
- [ ] Participant access does not create institutional authority.

## 6. Attribution

Every material workplace action must preserve:

```text
WHO
WHAT
WHEN
WHY / CONTEXT
UNDER WHICH AUTHORIZATION
REFERENCING WHICH INSTITUTIONAL OBJECT
```

- [ ] Creation time is preserved.
- [ ] Actual occurrence time is preserved where materially relevant.
- [ ] Cross-domain references preserve ownership boundaries.
- [ ] Historical communication remains reconstructable.

## 7. Non-collapse verification

- [ ] Worker ≠ Conversation.
- [ ] Conversation ≠ Work.
- [ ] Conversation ≠ Assignment.
- [ ] Conversation ≠ Authorization.
- [ ] Meeting ≠ Decision.
- [ ] Meeting ≠ Assignment.
- [ ] Queue item ≠ Work.
- [ ] Queue item ≠ Authorization.
- [ ] Communication does not create authority.
- [ ] Communication does not create execution.
- [ ] Queue state does not silently mutate authoritative domain state.

## 8. Acceptance evidence

For each checked item, implementation evidence must identify the concrete test, execution record, or attributable observation demonstrating the behavior. A checked box without evidence does not constitute Phase 3 acceptance.

`PHASE_3_GATE.md` remains `PENDING` until this checklist and the required acceptance evidence are complete.
