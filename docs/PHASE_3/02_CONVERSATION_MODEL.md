# METATRON WORKFORCE — PHASE 3 / 02 CONVERSATION MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Depends on:** `01_WORKPLACE_COMMUNICATION_MODEL.md`

## 1. Purpose

Define the canonical institutional conversation construct used for Human ↔ Worker and Worker ↔ Worker communication.

A conversation is a contextual communication container. It is not an authority container and is not itself a work item.

## 2. Canonical concepts

| Concept | Meaning |
|---|---|
| Conversation | Persistent communication context |
| Participant | Actor admitted to the conversation |
| Message | Attributable communication within the conversation |
| Context Reference | Link to Work, Assignment, Proposal, Meeting, Report, or other institutional object |
| Decision Reference | Link to a separately governed decision |
| Action Reference | Link to a separately governed action/work item |
| Evidence Reference | Link to evidence without changing evidence ownership |

## 3. Conversation lifecycle

```text
CREATED
  ↓
ACTIVE
  ↓
CLOSED
```

A closed conversation remains historically readable where policy permits. Closing communication does not delete the institutional history required for auditability.

## 4. Participation

Participation MUST be explicit enough to determine:

- who was included;
- when participation began;
- when participation ended where applicable;
- applicable visibility;
- applicable organizational context;
- applicable authorization.

## 5. Visibility

Visibility MUST be evaluated against authorization and organizational context. A participant's ability to read a conversation does not grant authority to act on every referenced object.

## 6. Decisions and actions

Conversation content may produce or reference a decision or action, but the authoritative object remains separate:

```text
Conversation
    ↓ references
Decision / Work / Assignment / Proposal
    ↓ governed by
Its own lifecycle + authority rules
```

## 7. Attachments and evidence

Attachments or evidence references MUST retain provenance and attribution. A conversation MUST NOT become the authoritative owner of external evidence merely because the evidence is discussed there.

## 8. Invariants

1. Conversation ≠ Work.
2. Conversation ≠ Assignment.
3. Conversation ≠ Authorization.
4. Conversation ≠ Execution.
5. Message ≠ institutional decision.
6. Participant access ≠ institutional authority.
7. Conversation history must remain attributable.
