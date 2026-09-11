# Worker Autonomy / Conformance Gap Closure

Status: IMPLEMENTATION + ACCEPTANCE CONTRACT — 2026-09-11

## Objective
Prove that a canonical persistent Worker actor can own real Workforce work from accepted Human Objective through planning, governed allocation/execution, failure recovery, independent Observation and evidence-backed completion without Human step-by-step intervention.

This closure does **not** create a second autonomy engine. Metatron already contains durable Objective management, planning, staffing, Assignment/Authorization, execution attempts, runtime replacement, bounded retry/replan, Observation and evidence/outcome reporting. The gap is to prove those existing institutional primitives compose through the Worker actor identity created by the Elastic Worker Actor Runtime closure.

## Canonical autonomy chain

```text
Human execution intent
        |
        v
Durable Objective acceptance
        |
        v
Management planning + Work Graph
        |
        v
Governed Worker allocation
Assignment + Authorization + ExecutionAttempt
        |
        v
Persistent Worker actor
actor:<worker-id>
        |
        v
Worker cognition / capability execution
        |
        +---- transient failure ----> bounded runtime replacement / dispatch recovery
        |                                |
        |                                +---- retry as same Worker identity
        v
Durable evidence-producing outcome
        |
        v
Independent Observation
        |
        v
Evidence-backed Objective completion + outcome report
```

## Invariants

```text
WORKER != MODEL
WORKER != RUNTIME INSTANCE
OBJECTIVE COMPLETION != EXECUTION SUCCESS
RETRY != NEW WORKER IDENTITY
RUNTIME REPLACEMENT != WORKER REPLACEMENT
FAILED EFFECT != VERIFIED COMPLETION
OBSERVATION IS INDEPENDENT FROM EXECUTION
HUMAN APPROVAL IS NOT REQUIRED FOR ROUTINE BOUNDED RECOVERY
```

## Existing primitives verified by this closure

- `HumanObjectiveIngressService` — durable Human execution-intent acceptance.
- `ManagementAutonomyService` — Objective ownership, lifecycle, work state, evidence and outcome reporting.
- `AutonomousManagementRunner` — planning, DAG progression, bounded recovery/replan and completion orchestration.
- `AutonomyCoordinationService` — versioned Work Graph, durable dispatch identity and reconciliation.
- `GovernedAutonomousExecutionCapability` — Worker allocation, Assignment, Authorization, execution admission, ExecutionAttempt, fencing and runtime recovery.
- `RuntimeCapacityCoordinator` — replaceable Worker runtime capacity while preserving Worker identity.
- `WorkerActorRuntime` — stable actor identity, per-Worker mailbox/state/work lane.
- `ActorScopedWorkerIntelligenceService` — cognition executed as the allocated Worker actor.
- `ObservationClosureService` — independent criterion verification before completion.
- `FounderWorkerWorkProductObservationVerifier` — independent reread of durable Worker cognitive work product.

## Acceptance proof

Canonical test:

`WorkerActorAutonomyConformanceAcceptanceTest`

The test intentionally exercises a non-happy path:

1. Form canonical `WORKER-COMPOSER-ARTIST` with durable Worker identity/Position/runtime profile.
2. Accept one Human Objective through the general execution ingress.
3. Persist and execute a verifiable Work plan targeting that exact Worker.
4. Governed allocation creates real Assignment/Authorization/ExecutionAttempt state.
5. Worker cognition runs through `actor:WORKER-COMPOSER-ARTIST`.
6. Cognition attempt 1 fails transiently.
7. Runtime is replaced; same Worker identity retries.
8. Cognition attempt 2 fails transiently and exhausts the inner bounded runtime-recovery budget.
9. Workforce management performs local bounded recovery without Human intervention and starts a fresh governed dispatch/Assignment.
10. Cognition attempt 3 succeeds as the same persistent Worker actor.
11. A durable Founder Worker work product is persisted.
12. Independent Observation rereads that product and returns PASS.
13. Work Graph and Objective become COMPLETED only after Observation passes.
14. Outcome report is emitted with durable evidence.

Required assertions include:

- exactly one stable actor ID: `actor:WORKER-COMPOSER-ARTIST`;
- actor mailbox records two failed cognition turns and one completed turn;
- every actor turn preserves Objective, Assignment and step provenance;
- three durable ExecutionAttempts: FAILED, FAILED, SUCCEEDED;
- governed recovery produces a cancelled failed Assignment and a completed recovery Assignment, both owned by the same Worker;
- one Workforce `LOCAL_RECOVERY` event and zero escalation events;
- zero dead letters;
- Observation verdict `PASSED`;
- durable Work Graph `COMPLETED`;
- Management Objective `COMPLETED`;
- evidence includes the durable Founder Worker work product;
- `ObjectiveOutcomeReportReady` is emitted.

## Recovery policy proven

Routine read-only/provider failures may recover autonomously within bounded retry budgets. Security/authorization failures and unsafe/unknown mutating effects remain fail-closed and are not blindly retried. Exhausted bounded recovery escalates instead of looping forever; those contracts remain covered by the existing `AutonomousManagementRecoveryTest` and governed execution recovery tests.

## Definition of done

Worker Autonomy / Conformance is closed when:

1. the actor-autonomy E2E acceptance passes;
2. the complete Gradle build passes;
3. repository CI/Highway conformance passes;
4. the change is merged to canonical `main`;
5. production is deployed from the exact merged immutable SHA;
6. production identity, liveness/readiness and public Gateway health are verified.

## Scope boundary / next upgrade

This closure proves autonomous institutional work through a persistent canonical Worker actor using the current provider-independent Intelligence fabric. It does not train a proprietary foundation model and does not bind a Worker to one provider.

The next upgrade layer is **Intelligence Routing Correctness / Intelligence Fabric**: capability-aware provider selection, fallback, quality/cost/latency policy and evaluation while preserving Worker identity independently from GPT/Gemini/Claude provider choice.
