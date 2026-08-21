# METATRON WORKFORCE — PHASE 2 / 01 CANONICAL ENTITY MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic authority:** `WORKFORCE_SOT.md`
**Implementation architecture:** `WORKFORCE_IMPLEMENTATION_ARCHITECTURE.md`
**Canonical semantic model:** `WORKFORCE_DOMAIN_DATA_STATE_EVENT_MODEL.md`

## 1. Purpose

This artifact decomposes the approved Workforce Phase 2 semantic model into canonical implementation-facing concepts. It does not prescribe database tables, ORM classes, API resources, or service boundaries.

A semantic concept is not automatically a persistence object.

## 2. Canonical entity set

| Entity / record type | Meaning | Identity required |
|---|---|---|
| Participant | Recognized entity capable of Worker admission | participant_id |
| Worker | Persistent Workforce identity anchor | worker_id |
| Participation | Worker relationship with an organization/context | participation_id |
| Organization Context | Institutional organization/unit/team/department context reference | organization_context_id |
| Position | Institutional placement in organizational structure | position_id |
| Role | Responsibility/position definition occupied in context | role_id |
| Reporting Relationship | Explicit operational relationship between organizational participants | reporting_relationship_id |
| Capability | Worker-associated ability or demonstrated capacity | capability_id |
| Qualification | Evidence-backed satisfaction of a defined standard | qualification_id |
| Authority Grant | Legitimately granted institutional power | authority_id |
| Delegation | Bounded transfer/extension of authority | delegation_id |
| Work | Legitimate institutional work object | work_id |
| Proposal | Proposed plan/action/request before commitment/execution | proposal_id |
| Assignment | Binding of Worker to legitimate work/responsibility | assignment_id |
| Schedule | Planned temporal commitment | schedule_id |
| Availability | Periods in which Worker is operationally available | availability_id |
| Capacity | Bounded work absorption during a period | capacity_id |
| Resource Context | Resources required, allocated, consumed, or constrained | resource_context_id |
| Authorization Decision | Contextual resolution of whether an action is permitted | authorization_id |
| Runtime | Ephemeral operational environment for execution | runtime_id |
| Execution Reference | Attributable realization of work; Execution remains externally owned | execution_id |
| Outcome | Result of execution | outcome_id |
| Observation | Recorded observation about behavior/state/outcome | observation_id |
| Evidence | Attributable material supporting a claim | evidence_id |
| Report | Institutional report concerning work/status/outcome/performance/economics | report_id |
| Review | Contextual evaluation of proposal/work/report/outcome/performance | review_id |
| Performance | Contextual evaluation derived from attributable work/evidence | performance_id |
| Experience | Historical record/interpretation of participation and consequences | experience_id |
| Reflection | Structured examination of experience, outcome, and variance | reflection_id |
| Learning | Worker-associated adaptive state formed from evaluated experience | learning_id |
| Improvement Candidate | Proposed evidence-derived change in behavior/practice/capability/planning | improvement_id |
| Improvement Validation | Evaluation against an appropriate baseline/comparison | validation_id |

## 3. Canonical semantic graph

```text
PARTICIPANT
  ↓ recognition / admission
WORKER
  ↓
PARTICIPATION
  ↓
ORGANIZATION CONTEXT
  ├── POSITION
  ├── ROLE
  └── REPORTING

WORKER
  ├── CAPABILITY → QUALIFICATION → EVIDENCE / PROVENANCE
  ├── AUTHORITY → DELEGATION
  ├── WORK → PROPOSAL → ASSIGNMENT
  ├── SCHEDULE / AVAILABILITY / CAPACITY / RESOURCE CONTEXT
  └── AUTHORIZATION → RUNTIME → EXECUTION
                              ├── OUTCOME
                              ├── OBSERVATION
                              ├── EVIDENCE
                              └── REPORT → REVIEW → PERFORMANCE

EXECUTION / OUTCOME / EVIDENCE
  ↓
EXPERIENCE
  ↓
REFLECTION
  ↓
LEARNING
  ↓
IMPROVEMENT CANDIDATE
  ↓
VALIDATION
  ↓
ADOPTION / FUTURE BEHAVIOR
```

## 4. Relationship semantics

```text
Participant 1 → Worker 0..1
Worker 1 → Participation 0..N
Participation 1 → Organization Context 1
Participation 0..N → Role
Worker 0..N → Capability
Capability 0..N → Evidence
Capability 0..N → Qualification
Worker 0..N → Authority Grant
Authority Grant 0..N → Delegation
Worker 0..N → Work / Assignment
Work 0..N → Proposal
Work 0..N → Assignment
Assignment 0..N → Authorization Decision
Authorization Decision 0..N → Execution
Execution 0..N → Runtime
Execution 0..N → Outcome
Execution 0..N → Observation
Execution 0..N → Evidence
Execution 0..N → Report
Report 0..N → Review
Execution / Outcome / Evidence 0..N → Experience
Experience 0..N → Reflection
Reflection 0..N → Learning
Learning 0..N → Improvement Candidate
Improvement Candidate 0..N → Validation
```

These cardinalities are semantic expressions, not locked database cardinalities.

## 5. Non-collapse invariants

The implementation MUST preserve these distinctions:

- Worker ≠ Runtime
- Worker ≠ Role
- Worker ≠ Position
- Capability ≠ Authority
- Authority ≠ Authorization
- Assignment ≠ Authorization
- Authorization ≠ Execution
- Execution ≠ Outcome
- Observation ≠ Evidence
- Evidence ≠ Truth
- Experience ≠ Knowledge
- Learning ≠ Institutional Knowledge
- Workforce ≠ Economy

## 6. Identity invariants

Stable identifiers must remain referentially stable across:

- multiple runtimes;
- multiple assignments;
- multiple executions;
- multiple organizational relationships;
- multiple learning cycles.

IDs MUST NOT encode transient runtime state.

## 7. Implementation constraint

The next artifacts define ownership, lifecycle, commands, events, temporal semantics, provenance, attribution, authorization references, economic evidence, and learning lineage. They refine this canonical concept model without changing its semantic authority.