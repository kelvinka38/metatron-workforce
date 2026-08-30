# WORKFORCE AUTONOMY CLOSURE — TRACEABILITY AND EVIDENCE PLAN

**Status:** ACTIVE IMPLEMENTATION CONTROL  
**Purpose:** Keep canonical decisions, code, tests, deployment and production evidence connected.

## 1. Required PR traceability

Every autonomy-closure PR SHALL include:

```text
Canonical decision IDs/clauses
Affected domain owners and contracts
Implementation files/components
Persistent state/schema changes
Idempotency/fencing behavior
Failure/reconciliation behavior
Unit/integration tests
Production acceptance gates advanced
Evidence level achieved
Known remaining gaps
```

## 2. Decision-to-delivery matrix

| Canonical decision group | Primary implementation area | Required evidence |
|---|---|---|
| Objective durable unit; interaction detachment | ingress, Objective store, owner transaction, outbox | disconnect/restart/replay production test |
| Gateway admission != Workforce acceptance | Gateway adapter and acceptance API | state/ack ordering and degraded-mode proof |
| Exactly one accountable owner | ownership store, CAS/fencing, Manager Runner | concurrent Runner/transfer adversarial test |
| Workforce Management loop | Runner, state machine, timers, reconciliation | unfinished Objective autonomous progression |
| Intelligence != management | Intelligence request/proposal adapter | Manager decision and persisted plan provenance |
| Versioned Work Graph | graph store, ready-set scheduler | fan-out/join/replan/stale-node proof |
| Capability/capacity allocation | Workforce Core adapter and reservations | finite-capacity contention test |
| Autonomous staffing | staffing runner, admission/formation adapters | real gap resolved without manual API sequence |
| Worker/model/runtime separation | identity and runtime binding | provider/runtime replacement continuity |
| Assignment/Authorization/Execution separation | dispatch pipeline | denied/revoked/stale authorization tests |
| Durable cross-domain exchange | outbox/inbox, dedupe, dead-letter | duplicate/drop/reorder/reconcile matrix |
| Execution recovery | attempts, leases, heartbeat, checkpoint | kill/expire/stale-attempt production proof |
| Observation closure | criterion verifier and evidence package | success-without-evidence remains non-complete |
| Workplace/Meeting reuse | projections, Conversation/Meeting/Decision refs | cross-channel continuity and no duplicate truth |
| Budget/risk governance | Economy envelope and scheduler guards | threshold/kill-switch/replan/escalation proof |
| BIOS boundary | live service adapter and correlation | BIOS Program -> Workforce Objective -> verified Outcome |
| L10 claim | all four golden slices | exact-SHA independent production verdict |

## 3. Evidence levels

Label every artifact:

```text
D0 DOCUMENTED
D1 IMPLEMENTED
D2 UNIT_TESTED
D3 INTEGRATION_TESTED
D4 DEPLOYED
D5 PRODUCTION_PROBED
D6 BOUNDED_PRODUCTION_ACCEPTED
D7 GENERAL_L10_ACCEPTED
```

Do not promote an artifact without new evidence. A test that manually calls transitions can reach D3 for composition but cannot prove autonomous management behavior.

## 4. Golden-slice evidence bundle

Each production run SHALL capture:

- canonical document commit SHA;
- implementation commit/deployed image SHA;
- environment identity;
- submission/idempotency/objective identity;
- owner and fencing versions;
- graph versions and scheduler decisions;
- Worker/capability/capacity/Assignment records;
- authorization decisions/revocations;
- execution attempts, lease/heartbeat/checkpoints;
- injected failures and autonomous recovery;
- Observation criterion results/evidence;
- cost/resource/risk records;
- Workplace/channel disconnect and delivery evidence;
- final closure package;
- independent gate verdict.

## 5. CI versus production

CI protects contracts and regressions. Production acceptance proves institutional behavior. Both are required; neither substitutes for the other.

A workflow MUST identify which steps are setup/test-driver actions and which transitions were autonomously produced by the deployed Management Runner/Scheduler/Execution/Observation loop.

## 6. Claim owner

No component owner may self-generalize a bounded success. Formal autonomy status is recorded only after the full acceptance package is reviewed against the upstream gate.
