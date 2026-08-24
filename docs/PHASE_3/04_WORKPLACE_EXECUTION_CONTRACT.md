# METATRON WORKFORCE — PHASE 3 / WORKPLACE EXECUTION CONTRACT

**Authority:** `PHASE_3_GATE.md` and the Phase 3 communication/conversation/meeting models.
**Purpose:** Freeze the executable boundary between the Phase 3 institutional workplace semantics and implementation acceptance.
**Status:** FROZEN — implementation acceptance contract.

## 1. Scope

Phase 3 implements the institutional workplace through which Humans and Workers communicate and coordinate work. It owns workplace coordination state only. It does not become the source of truth for authorization, governance, execution, observation, or external-domain outcomes.

## 2. Canonical objects

| Object | Responsibility | Explicit non-responsibility |
|---|---|---|
| `Conversation` | persistent communication context | authority, work, execution |
| `Message` | attributable communication | decision, authorization, execution |
| `Meeting` | coordination context and lifecycle | decision authority, assignment authority, execution |
| `WorkQueueItem` | operational queue/reference state | authoritative domain mutation |
| `AuthorizationContext` | authorization result carried into workplace actions | policy ownership |
| `ActorRef` | actor identity reference | runtime identity |

## 3. Required interaction paths

The implementation MUST prove these paths:

1. Human → Head Worker.
2. Human → authorized subordinate Worker.
3. Human → any authorized Worker.
4. Worker → Human.
5. Worker → Worker.
6. Meeting creation/request and participation.
7. Work request → institutional queue → response/report.
8. Proposal/review/approval/assignment/report/escalation/notification references through the queue.
9. Outstanding-work visibility.
10. Attribution and authorization correlation for every material action.

## 4. Conversation contract

A conversation MUST preserve:

- conversation identity;
- initiating actor;
- participants;
- organization context;
- creation time;
- attributable messages;
- authorization correlation.

Only admitted participants may send messages. The organization context of a message MUST equal the conversation context. Authorization is evaluated for each material communication action.

## 5. Meeting contract

A meeting MUST preserve organizer, participants, organization, purpose, agenda, scheduled/actual times, lifecycle state, discussion references, decision references, action references, evidence references, and minutes reference where applicable.

Lifecycle:

```text
PLANNED → SCHEDULED → HELD → MINUTES_PENDING → CLOSED
```

Cancellation is permitted only before a held/closed state. Meeting coordination MUST NOT manufacture decision or execution authority.

## 6. Work queue contract

`WorkQueueItem` is a coordination/reference object. It may represent:

- REQUEST
- ASSIGNMENT_REFERENCE
- APPROVAL_REQUEST
- REVIEW_REQUEST
- REPORT
- ESCALATION
- MEETING_INVITATION
- PROPOSAL
- NOTIFICATION

Queue lifecycle:

```text
CREATED → DELIVERED → ACKNOWLEDGED → RESOLVED
                               └──────→ DISMISSED
                               └──────→ ESCALATED
```

Queue transitions MUST NOT mutate the referenced authoritative object. The reference remains an identifier owned by its authoritative domain.

## 7. Authorization boundary

Workplace services MUST consume an externally supplied `AuthorizationPolicy`. A denied authorization result MUST prevent creation or lifecycle mutation. Successful actions MUST retain the authorization identifier.

The workplace MUST NOT replace Gateway authorization or infer authority from message, conversation, meeting, or queue state.

## 8. Attribution and visibility

Every material workplace action MUST retain actor and organization context. Historical records remain attributable. Visibility is an authorization concern; participant access does not imply authority over referenced institutional objects.

## 9. Runtime boundary

Phase 3 does not own Worker runtime lifecycle. Runtime execution is downstream of the workplace workflow:

```text
Workplace communication / request
        ↓
queue or institutional work reference
        ↓
applicable assignment + authorization
        ↓
execution boundary
        ↓
Worker runtime
```

`RuntimeExecutionBinding` may correlate execution identity, assignment identity, authorization identity, and runtime identity, but Phase 3 does not manufacture execution authority.

## 10. Acceptance evidence

G3 acceptance requires:

- communication tests for Human/Worker and Worker/Worker paths;
- authorization-denial tests;
- conversation attribution/context tests;
- meeting lifecycle and authorization tests;
- queue lifecycle, outstanding-work and overdue tests;
- queue-reference immutability/boundary tests;
- proposal/review/approval/report/escalation/notification queue coverage;
- runtime boundary regression tests;
- full Gradle regression;
- clean working tree and exact certified commit;
- fresh GitHub Actions PASS on the acceptance workflow.

## 11. Non-negotiable invariants

1. Communication ≠ authorization.
2. Communication ≠ execution.
3. Conversation ≠ Work.
4. Conversation ≠ Assignment.
5. Meeting ≠ Decision.
6. Meeting ≠ Assignment.
7. Queue state ≠ authoritative domain state.
8. Participant visibility ≠ authority.
9. Worker identity ≠ conversation identity.
10. Worker identity ≠ runtime identity.
11. Cross-domain references do not transfer ownership.
12. Every material action remains attributable.

## 12. Gate transition

This contract is the frozen implementation contract for G3. Implementation is considered complete only when the Phase 3 acceptance workflow proves the required paths and invariants. A successful G3 gate permits progression to Phase 4 — Organization / Relationships.
