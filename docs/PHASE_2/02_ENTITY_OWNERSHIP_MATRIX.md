# METATRON WORKFORCE — PHASE 2 / 02 ENTITY OWNERSHIP MATRIX

**Purpose:** make authoritative ownership explicit before state/event implementation.

## 1. Ownership rule

Every authoritative state has one authoritative owner. Other domains/modules may read, cache, derive, observe, or reference it, but must not silently become competing authorities.

Shared persistence does not imply shared ownership.

## 2. Workforce ownership matrix

| Concept | Workforce ownership | External authority / boundary |
|---|---|---|
| Participant reference | Reference/operational representation | Institutional identity/recognition authority |
| Worker | YES | Constitutional identity remains external |
| Participation | YES, Worker-side relationship | Organization/institutional admission rules |
| Organization Context | Reference/operational context | Organization domain |
| Position | YES operational representation | Organization authority |
| Role | YES operational representation | Governance/policy where authority is defined |
| Reporting Relationship | YES operational relationship | Organization authority |
| Capability | YES operational representation | Knowledge/credential evidence where applicable |
| Qualification | YES operational record | Applicable standard/assessment authority |
| Authority Grant | Worker-side representation | Governance / legitimate authority source |
| Delegation | YES representation | Delegator's legitimate authority |
| Work | YES operational work object | Requesting/owning domain for legitimacy |
| Proposal | YES workflow object | Applicable approval authority |
| Assignment | YES | Assignment authority / authorization boundary |
| Schedule | YES workforce planning record | Organization/context rules |
| Availability | YES workforce operational record | Worker/context constraints |
| Capacity | YES workforce operational model | Time/resource evidence |
| Resource Context | YES workforce-side requirement/usage evidence | Economy/resource domains |
| Authorization Decision | Coordination/result reference | Governance/Gateway/applicable authorization authority |
| Runtime | Workforce correlation reference | Runtime/execution infrastructure |
| Execution | NO — reference/correlation only | Execution |
| Outcome | Reference/evidence | Execution / Observation |
| Observation | NO — reference/consumer | Observation |
| Evidence | YES when Workforce-generated; references external evidence | Observation / external sources |
| Attribution | YES cross-domain evidence structure | Participating domains provide authoritative inputs |
| Report | YES | Reviewer/manager authority for acceptance |
| Review | YES workflow/evaluation record | Configured review authority |
| Performance | YES workforce contextual evaluation | Evidence/measurement sources |
| Experience | YES Worker-associated lineage | Execution / Observation evidence |
| Reflection | YES Worker-associated evaluation | Evidence lineage |
| Learning | YES Worker operational learning state | Knowledge admission remains external |
| Improvement Candidate | YES proposal state | Governance/policy/validation authority |
| Improvement Validation | YES process record | Configured evaluation authority |
| Institutional Knowledge | NO | Knowledge |
| Institutional Policy | NO | Governance / policy authority |
| Constitutional legitimacy | NO | Governance/institutional authority |
| Accounting / Treasury | NO | Economy |
| Execution infrastructure | NO | Execution |
| Observation authority | NO | Observation |
| Gateway enforcement | NO | Gateway |

## 3. Module ownership boundaries

### Identity & Participation
Owns Worker-side identity, admission relationship, participation, lifecycle identity state, and stable attribution references.

### Organization Relationships
Owns Worker-side reporting, supervision, coordination, role/position occupancy, team/department references, and escalation routing.

### Role / Capability / Qualification
Owns role occupancy, capability records, qualification state, evidence references, provenance, validity, and contextual scope.

### Authority & Delegation
Owns Worker-side representations of legitimate authority and delegation. It does not create legitimacy.

### Workplace & Communication
Owns conversations, messages, meeting participation, agendas, action items, work queues, escalation channels, decision references, and report delivery.

### Work & Planning
Owns work requests, proposals, plans, assignments, commitments, dependencies, acceptance criteria, expected outcomes, and reporting obligations.

### Time / Availability / Capacity
Owns working windows, shifts, breaks, leave/unavailability, time zone, concurrent-work constraints, scheduled/committed/remaining capacity.

### Staffing & Resources
Owns staffing requirements, staffing state, skill/shift gaps, coverage, labor demand, resource requirements, and capacity deficit evidence.

### Authorization Coordination
Coordinates contextual resolution; authoritative enforcement remains external where required.

### Execution Coordination
Owns correlation and attribution context around execution, not execution itself.

### Reporting & Review / Performance
Owns workforce reporting, review workflow, contextual performance evaluation, and variance presentation. Accounting remains Economy-owned.

### Experience / Learning / Improvement
Owns Worker-associated adaptive state and lineage. Institutional Knowledge and institutional evolution remain externally governed.

## 4. Hard boundary

A module MUST NOT mutate another module's authoritative state merely because implementation happens to share storage.

A cross-domain write requires an explicit contract and authoritative acceptance by the owning domain.