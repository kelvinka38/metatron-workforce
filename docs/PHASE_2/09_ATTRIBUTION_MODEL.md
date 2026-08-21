# METATRON WORKFORCE — PHASE 2 / 09 ATTRIBUTION MODEL

## 1. Purpose

Material institutional action must remain attributable through workflow, authorization, runtime, execution, outcome, reporting, performance, and learning.

## 2. Attribution questions

The implementation must be able to answer, where applicable:

```text
WHO?
WHAT?
WHEN?
WHY?
UNDER WHICH ROLE?
UNDER WHICH PARTICIPATION?
UNDER WHICH AUTHORITY?
UNDER WHICH ASSIGNMENT?
UNDER WHICH AUTHORIZATION?
IN WHICH CONTEXT?
THROUGH WHICH RUNTIME?
USING WHICH RESOURCES?
WITH WHICH INPUTS?
WHAT RESULT?
WHAT EVIDENCE?
```

## 3. Attribution chain

```text
Human / Worker instruction
        ↓
Decision / proposal
        ↓
Approval / policy resolution
        ↓
Assignment
        ↓
Authorization
        ↓
Runtime
        ↓
Execution
        ↓
Outcome / observation / evidence
        ↓
Report / review
        ↓
Performance / experience
        ↓
Learning / improvement
```

## 4. Minimum attribution context

A material Workforce-generated record should retain, where applicable:

- actor/worker identity;
- organization/context;
- role/position context;
- participation;
- assignment;
- authorization;
- runtime;
- request/command;
- timestamp(s);
- resource context;
- evidence references;
- outcome reference;
- correlation/causation reference.

## 5. Human ↔ Worker attribution

Institutional communication must preserve sender, recipient/participants, organizational context, timestamp, authorization context, and provenance where applicable.

## 6. Worker ↔ Worker attribution

Worker-to-Worker communication, meetings, decisions, action items, reports, and escalations must preserve participating identities and context. Human mediation is not required for every interaction, but attribution remains required.

## 7. Execution attribution

Workforce execution coordination must preserve enough context to correlate:

```text
execution_id
worker_id
participation_id
role_context
assignment_id
authorization_id
runtime_id
start_time
end_time
result
failure
outcome_reference
evidence_reference
```

Execution itself remains Execution-owned.

## 8. Reporting attribution

Reports must distinguish, where applicable:

```text
FACT
OBSERVATION
INFERENCE
RECOMMENDATION
DECISION
```

Workers must not present inference as fact.

## 9. Learning attribution

An improvement claim must be traceable to:

- prior behavior;
- evidence;
- evaluation;
- baseline/comparison;
- change proposal;
- validation result;
- adoption decision;
- subsequent outcome.

## 10. Failure attribution

Failure must remain attributable. This includes unauthorized actions, unavailable Workers, capacity deficit, staffing shortage, unavailable resources/dependencies, invalid assignments, expired authority, execution failure, outcome failure, policy violation, and evidence insufficiency.

## 11. Implementation constraint

Attribution is a semantic requirement. The storage representation and exact correlation identifiers are implementation decisions subordinate to this model.