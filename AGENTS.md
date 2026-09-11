# METATRON WORKFORCE — AGENT / WORKER GOVERNING ENTRY

## RATIFIED WORKFORCE AUTONOMY CLOSURE — MANDATORY CURRENT BASELINE

Founder approved Workforce Autonomy Closure on 2026-08-31. Production acceptance completed on 2026-09-01.

Current general autonomy status for the Founder-ratified Autonomy Closure scope is:

```text
TECHNICALLY COMPLETE / PRODUCTION AUTONOMY ACCEPTED
ACCEPTED_L10
```

Accepted production baseline:

- exact source/deployed SHA: `c7d19e67797b1f97ba118433bc749bb80defe9d0`
- Golden Slices: `4/4 PASS`
- mandatory production conditions: `45/45 PASS`
- unresolved critical contradictions: `0`
- final ratification run: `33465915027`
- final artifact: `9784967732`
- artifact SHA-256: `1aa44620296eb9bc159ecca81b9c2473370b1b69712d8ef1d601c0c39c69e369`

Before work on ingress, management, staffing, scheduling, runtime, Workplace, Intelligence handoff, BIOS handoff, Observation or completion claims, read `docs/AUTONOMY_CLOSURE/README.md`, `docs/AUTONOMY_CLOSURE/FINAL_ACCEPTED_L10_EVIDENCE.md`, and every upstream canonical contract they list.

Do **not** reset the Autonomy Closure program to `PARTIAL`, P0, P1 or another historical gate merely because later product work exists. P0–P10 are closed for this accepted scope. There is no canonical P11 in this program. Reopening requires contradictory production evidence, a materially changed scope that requires new acceptance, or an applicable new canonical decision.

Non-negotiable accepted target:

> Once Workforce durably accepts an Objective and assigns its accountable Manager Worker, Objective lifetime no longer depends on ChatGPT, Telegram, another channel, a model session, a process, or a runtime remaining alive.

Every relevant future PR must still identify the canonical clauses, domain owners, durability/idempotency/fencing behavior, failure/reconciliation behavior, evidence level and any production gate required by the changed scope.

## FOUNDER-APPROVED SOT ENFORCEMENT PROGRAM — CURRENT EXECUTION GOVERNANCE

Founder approved the SoT Enforcement Detailed Gap Closure on 2026-09-10. This is a post-Autonomy-Closure governance implementation program; it does not reopen the accepted Autonomy Closure.

Before any consequential design, implementation, mutation, deployment, or completion work in this repository, read in this order:

1. upstream canonical `kelvinka38/universal/SOT_DISCOVERY_PROTOCOL.md`;
2. upstream canonical `kelvinka38/universal/SOT_DERIVATION_PROTOCOL.md`;
3. upstream canonical `kelvinka38/universal/SOT_CHANGE_CONTROL.md`;
4. `kelvinka38/metatron-institution/14_EXECUTION/SOT_ENFORCEMENT_DETAILED_GAP_CLOSURE.md`;
5. `docs/SOT_ENFORCEMENT_IMPLEMENTATION_DETAIL_CLOSURE.md`;
6. `docs/SOT_ENFORCEMENT_EXECUTION_PLAN.md` for implementation sequencing.

Until runtime enforcement is fully implemented, every Human/AI/Worker operating through repository tooling MUST manually respect the same boundary:

```text
NO APPLICABLE SOT DISCOVERY -> NO MATERIAL ACTION
NO VERIFIED DERIVATION -> NO CONSEQUENTIAL EXECUTION PLAN
NO APPROVED PLAN -> NO CONSEQUENTIAL MUTATION
MATERIAL PLAN DEVIATION -> STOP AFFECTED SCOPE + CHANGE PROPOSAL
MODEL/WORKER COMPLETE CLAIM != INSTITUTIONAL COMPLETION
```

A reasoning actor may propose a better design outside the currently approved plan, but it MUST classify that as a change proposal. It MUST NOT silently implement that redesign under an existing approval.

Do not create a parallel execution/governance stack. The current implementation design explicitly reuses `ExecutionAdmissionService`, `ExecutionAttemptService`, `ActionFabric`, `CognitiveWorkerRuntime`, management lifecycle, existing lease/fencing and evidence infrastructure.

No model/provider/channel/Worker receives authority merely from confidence, capability, technical credentials, repository write access, or role identity.

## FOUNDER-APPROVED ELASTIC EXECUTION WORKSPACE & RESOURCE ISOLATION PROGRAM

Founder approved the Final Proposal and six architecture amendments on 2026-09-12. This program closes the concurrency gap proven when concurrent agents interfered through the same mutable checkout/build/branch/deployment source. It does not reopen Worker identity or Repository Control Plane closures and MUST NOT create duplicate institutional authorities.

Before changing execution concurrency, mutable repository workspaces, build isolation, resource scheduling/leases/fencing, integration/merge queues, operator mutation paths, or deployment materialization, read in this order:

1. `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_GAP_CLOSURE.md`;
2. `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_DETAILED_SPEC.md`;
3. `docs/ARCHITECTURE/ELASTIC_EXECUTION_WORKSPACE_RESOURCE_ISOLATION_EXECUTION_PLAN.md`.

Required ownership chain:

```text
Objective / Work Graph
        ↓
AutonomySchedulingService          # WHAT is eligible / worker allocation
        ↓
ExecutionAttempt                   # canonical attempt lifecycle + attempt fencing
        ↓
ExecutionResourceScheduler         # WHEN / WHERE infra capacity is granted
        ↓
ExecutionResourceManager           # shared-resource ownership + resource fencing
        ↓
ExecutionWorkspaceManager          # per-attempt mutable workspace
        ↓
Governed Capability / ActionFabric
        ↓
RepositoryIntegrationController
        ↓
Highway / Release Controller       # release authority remains Highway
```

Non-negotiable anti-duplication rules:

```text
DO NOT create a second ExecutionAttempt lifecycle.
DO NOT create a scheduler parallel to AutonomySchedulingService for Work eligibility.
DO NOT collapse ExecutionAttempt fencing and ResourceLease fencing into one token.
DO NOT create a second mutable workspace authority beside ExecutionWorkspaceManager; ObjectiveWorkspaceService migrates to a facade.
DO NOT create a second Repository Control Plane or ActionFabric.
DO NOT create a second release/production lock universe beside Highway; Highway resource claims must converge through the canonical Resource Manager.
DO NOT require mutable isolated workspaces for safe read-only canonical-mirror inspection.
DO require an ExecutionAttempt + isolated workspace for normal repository mutation by Human, Worker, ChatGPT, Claude, Gemini or other operator AI.
```

The primary acceptance regression remains: two simultaneous same-repository clean/build executions must use distinct mutable workspace/branch/build state and produce zero cross-agent interference.

Founder approval authorizes implementation of this contract; it is not itself production acceptance. The program is complete only after its EE acceptance matrix, CI and exact production evidence pass.

## Status

**MANDATORY REPOSITORY ENTRY POINT**

This file applies to every Human, Worker, AI coding agent, automation, reviewer, and implementation process operating in this repository.

## Governing order

Always resolve work in this order:

```text
UPSTREAM CANONICAL SOT / POLICY
        ↓
APPROVED DOMAIN ARCHITECTURE
        ↓
DETAILED ARCHITECTURE / TRACEABILITY
        ↓
IMPLEMENTATION CONTRACTS
        ↓
CODE / TESTS / RUNTIME
        ↓
EVIDENCE
```

Canonical SOT and policy always win over downstream documentation or implementation.

A downstream feature may extend product/runtime capability when it remains inside canonical semantic, ownership, authority, evidence, execution, observation, Knowledge, and Workforce boundaries.

> **Canonical compliance is a constraint test, not an innovation veto.**

Do not weaken a useful feature merely because it is not itself a Universal primitive. Do not promote an application/runtime construct into canonical semantics without the correct upstream governance.

## Mandatory Intelligence entry point

Before changing any Intelligence, conversational, LLM/provider, reasoning, retrieval, Worker-intelligence, meeting-intelligence, memory/context, tool-use, execution-from-intelligence, or learning-from-intelligence behavior:

1. Read `docs/ARCHITECTURE/INTELLIGENCE/README.md` first.
2. Then read all three Founder-approved baseline documents in that directory, in numbered order:
   - `docs/ARCHITECTURE/INTELLIGENCE/01_FINAL_ARCHITECTURE_PROPOSAL.md`
   - `docs/ARCHITECTURE/INTELLIGENCE/02_DETAILED_ARCHITECTURE.md`
   - `docs/ARCHITECTURE/INTELLIGENCE/03_TRACEABILITY_MATRIX.md`
3. Check stronger upstream canonical SOT/policy before implementation.

The old top-level `METATRON_INTELLIGENCE_*.md` files are compatibility pointers only. They are not competing architecture baselines.

These documents are subordinate to upstream canonical SOT/policy but are the approved Workforce engineering baseline for Intelligence work.

## Non-negotiable Intelligence rules

```text
LLM != METATRON
LLM != WORKER
MODEL PROVIDER != INSTITUTIONAL ROLE
INTELLIGENCE != KNOWLEDGE
INTELLIGENCE != AUTHORITY
CAPABILITY != AUTHORITY
INTENT != AUTHORIZATION
CLAIM != EVIDENCE
MODEL_BELIEF != TRUTH
CONSENSUS != CORRECTNESS
PROPOSAL != ASSIGNMENT
DECISION != EXECUTION
EXECUTION != OUTCOME
```

- Human interaction is natural-language-first.
- Frontier models provide general multilingual understanding, translation, slang/typo resolution, semantic interpretation, and natural-language expression. Do not rebuild a competing general-purpose NLP/translation/slang engine inside Metatron.
- Raw Human language MUST NOT be routed directly into institutional execution by keyword/regex/continuation heuristics as the principal semantic architecture.
- Deterministic computation is used where sufficient only after the request has crossed the appropriate semantic/normalized-request boundary when natural-language understanding is required.
- Provider unavailability is an explicit failure state; it does not authorize bypassing the approved semantic boundary.
- Intelligence Case is a runtime coordination construct and must not steal authoritative ownership from Worker, Workplace/Meeting, Authorization, Execution, Observation/Outcome, Knowledge, or other canonical domains.
- Workplace owns meetings. Intelligence may enhance deliberation but does not own the meeting.
- Workers are persistent institutional actors, never LLM personas or provider sessions.
- Information acquisition precedes unnecessary intelligence expenditure.
- Retrieve what Metatron can reliably obtain within authority/policy/resource limits before unnecessarily asking the Human.
- Multi-model reasoning is escalation, not default. Consensus is not truth.
- Intelligence cannot manufacture authority or authorization.
- Model output and learning candidates cannot automatically become institutional Knowledge.
- Channels do not own Intelligence, institutional memory, Worker identity, or authority.
- Subscription/entitlement may change resource and capability ceilings, never canonical truth discipline.

## Change rule

For any proposed feature or implementation change:

```text
1. Is it already canonically defined? → REUSE.
2. Does it contradict SOT/policy? → REJECT or REDESIGN.
3. Does it steal canonical ownership? → REDESIGN using contracts/references.
4. Is it compliant and valuable? → BUILD THE BEST VERSION.
```

After a plan has been approved, step 2 has a stricter execution interpretation: if the better solution materially contradicts the approved plan or governing SoT, stop the affected scope and enter governed change control. Do not silently substitute the new design.

Do not introduce keyword/continuation heuristics as the core semantic architecture for Human language understanding.

## Evidence rule

No material implementation claim is complete without the required evidence. Do not promote CI evidence to production evidence, model output to fact, or missing evidence to an architectural assumption.

The accepted Autonomy Closure baseline remains evidence-bound to its exact production scope. Future materially changed behavior must earn appropriate new evidence; it does not automatically inherit `ACCEPTED_L10` merely because this baseline is accepted.
