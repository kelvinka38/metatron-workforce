# METATRON WORKFORCE — PHASE 3 GATE

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 3 — Workplace / Communication
**Status:** ACCEPTANCE RUNNING
**Execution contract:** `04_WORKPLACE_EXECUTION_CONTRACT.md`

## 1. Objective

Phase 3 establishes the institutional workplace where Humans and Workers actually interact.

## 2. Required capabilities

The implementation must demonstrate:

- Human → Head Worker communication;
- Human → authorized subordinate Worker communication;
- Human → any authorized Worker communication;
- Worker → Human communication;
- Worker → Worker communication;
- conversation participation and visibility controls;
- meeting creation/request;
- meeting participation;
- agenda, discussion, decisions, action items, owners and deadlines;
- institutional inbox/work queue;
- requests;
- assignments/references;
- approvals;
- reviews;
- reports;
- escalations;
- meeting invitations;
- proposals;
- notifications;
- outstanding-work visibility;
- attributable communication history.

## 3. Acceptance checklist

- [x] Workplace communication model accepted.
- [x] Conversation model accepted.
- [x] Meeting model accepted.
- [x] Institutional work queue model accepted.
- [x] Workplace authorization boundary accepted.
- [x] Human can communicate with Head.
- [x] Human can communicate with authorized subordinate.
- [x] Human can create/request a meeting.
- [x] Human can participate in a meeting.
- [x] Human can issue/request work through the appropriate workflow.
- [x] Human can receive a Worker response.
- [x] Human can review a proposal.
- [x] Human can receive a report.
- [x] Human can see outstanding work.
- [x] Attribution is preserved.
- [x] Authorization boundaries are preserved.
- [x] Communication does not silently create authority.
- [x] Queue state does not silently mutate authoritative domain state.
- [x] Meeting decisions remain subject to their applicable authority/workflow.

The executable acceptance evidence for these conditions is `Phase3WorkplaceAcceptanceTest` plus the full regression and bootJar checks in `.github/workflows/phase3.yml`.

## 4. Non-negotiable boundaries

Phase 3 MUST NOT:

- replace Gateway authorization;
- create Governance authority;
- own Execution;
- own Observation;
- become the institutional source of truth for external domains;
- collapse communication, work, assignment, authorization, execution, or outcome into one object.

## 5. Gate decision

The gate remains non-PASS until the fresh Phase 3 GitHub Actions run proves the frozen contract on the exact current commit. A Phase 3 PASS permits progression to **Phase 4 — Organization / Relationships** under the Master Execution Plan.
