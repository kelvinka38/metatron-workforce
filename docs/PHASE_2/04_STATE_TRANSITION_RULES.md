# METATRON WORKFORCE — PHASE 2 / 04 STATE TRANSITION RULES

## 1. Transition contract

Every material transition MUST define:

- current state;
- requested next state;
- initiating actor;
- authority source;
- preconditions;
- temporal validity;
- evidence/provenance;
- resulting event;
- failure behavior.

Technical ability to call a function is never sufficient authority.

## 2. Transition authority matrix

| Construct | Transition | Minimum authority principle | Required evidence |
|---|---|---|---|
| Participant | recognized → admitted Worker | recognition/admission authority | admission evidence |
| Worker | admitted → active | applicable Worker activation rule | activation record |
| Worker | active → retired | authorized lifecycle authority | retirement reason/evidence |
| Participation | pending → active | organization/institutional admission rules | participation approval |
| Participation | active → suspended | authorized organizational authority | suspension reason |
| Participation | active/suspended → ended | authorized organizational authority | termination evidence |
| Authority | granted → active | legitimate authority source | grant + scope + validity |
| Authority | active → restricted | legitimate restricting authority | restriction reason |
| Authority | active → expired | temporal rule | expiry time |
| Authority | active → revoked | legitimate revocation authority | revocation evidence |
| Proposal | draft → submitted | proposal owner/requester | submitted proposal |
| Proposal | submitted → under review | configured workflow | review context |
| Proposal | under review → approved | configured approval authority | approval decision |
| Proposal | under review → rejected | configured approval authority | rejection decision |
| Proposal | under review → returned | configured reviewer | return reason |
| Proposal | under review → deferred | configured authority | deferral reason |
| Assignment | proposed → approved | authorized manager/decision right | assignment approval |
| Assignment | approved → active | assignment conditions satisfied | activation evidence |
| Assignment | active → completed | completion authority/workflow | completion evidence |
| Assignment | active → cancelled | authorized cancellation | cancellation reason |
| Authorization | requested → resolving | authorization coordinator/resolver | request context |
| Authorization | resolving → allow | applicable authorization policy | decision + reasons |
| Authorization | resolving → deny | applicable authorization policy | denial reasons |
| Authorization | resolving → review | configured review requirement | review requirement |
| Authorization | resolving → defer | configured deferral rule | deferral reason |
| Execution | requested → validating | execution coordination | request correlation |
| Execution | validating → authorized | valid authorization + conditions | authorization reference |
| Execution | authorized → running | execution admission conditions | start evidence |
| Execution | running → completed | execution result accepted | completion evidence |
| Execution | running → failed | execution failure | failure evidence |
| Execution | running → blocked | blocking condition | block evidence |
| Execution | running → cancelled | authorized cancellation | cancellation evidence |
| Report | draft → submitted | report author | submitted report |
| Report | submitted → under review | review workflow | review request |
| Report | under review → accepted | configured reviewer | review decision |
| Report | under review → returned | configured reviewer | return reason |
| Report | under review → rejected | configured reviewer | rejection reason |
| Improvement | observed → reflected | Worker/learning process | observation/experience |
| Improvement | reflected → proposed | Worker/authorized proposer | reflection + candidate |
| Improvement | proposed → evaluated | configured evaluation process | evaluation input |
| Improvement | evaluated → validated | configured validation authority/process | baseline/comparison |
| Improvement | evaluated → rejected | configured evaluation authority/process | rejection reason |
| Improvement | evaluated → inconclusive | configured evaluation authority/process | insufficiency evidence |
| Improvement | validated → adopted | applicable operational/policy authority | adoption decision |

## 3. Revalidation rule

Authorization and other time-sensitive decisions MUST be re-evaluated when material inputs change, including:

- delegation revoked;
- assignment cancelled;
- Worker suspended;
- authority expired;
- resource unavailable;
- budget unavailable;
- policy changed;
- operating window closed.

## 4. Failure rule

A rejected or failed transition MUST NOT silently mutate the target state.

Failure MUST preserve attributable evidence and, where applicable, create an escalation or review path.

## 5. Historical rule

A later transition must not rewrite historical truth merely because the current state changed.

Creation, effective, scheduled, actual, completion, expiry, revocation, and recording times must remain distinguishable where materially relevant.

## 6. Cross-domain rule

A Workforce transition cannot manufacture authority owned by Governance, Gateway, Execution, Observation, Knowledge, Economy, or another external domain. External acceptance is distinct from Workforce publication of a local fact.