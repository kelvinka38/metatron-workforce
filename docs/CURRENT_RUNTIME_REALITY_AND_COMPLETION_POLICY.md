# METATRON WORKFORCE — CURRENT RUNTIME REALITY & COMPLETION POLICY

**Status:** CURRENT-RUNTIME TRUTH / PRODUCT-USABILITY TRUTH / COMPLETION-CLAIM GUARDRAIL  
**Runtime audit date:** 2026-09-12  
**Audited production SHA:** `f7563203974ed53829fef128bbd1d1b047cd05bc`  
**Repository main after documentation-only correction:** `7d84ecdd470c46b9ee648de1ac47f1a833026580`

---

## 1. Purpose

This document is the canonical current-state boundary for statements about what Metatron Workforce:

- has implemented;
- has production-composed;
- exposes to a Human or AI client;
- can actually use at the current moment;
- has accepted end-to-end;
- may truthfully call complete.

It exists because those states are not equivalent.

Historical or scope-bounded acceptance MUST NOT be generalized into a claim that the current whole Workforce product is complete.

Likewise, the existence of backend code MUST NOT be generalized into a claim that the capability is currently usable from a product surface.

---

## 2. Mandatory capability-state vocabulary

The following terms are distinct:

```text
IMPLEMENTED
= the capability exists in source with its required implementation path.

PRODUCTION-COMPOSED
= the running production system contains/wires that capability.

EXPOSED
= an intended authorized Human/AI/product surface can invoke the capability.

OPERATIONALLY AVAILABLE
= required live dependencies are currently healthy enough for the path to run.

PRODUCT-USABLE
= an intended user can successfully use the capability without requiring
  internal architecture knowledge, unsupported syntax, hidden compatibility
  paths, or manual infrastructure intervention.

END-TO-END ACCEPTED
= the declared scope has passed its required exact-identity acceptance and
  production evidence gates.
```

Therefore:

```text
IMPLEMENTED
!= PRODUCTION-COMPOSED
!= EXPOSED
!= OPERATIONALLY AVAILABLE
!= PRODUCT-USABLE
!= END-TO-END ACCEPTED
```

A report MUST state the strongest level actually proven.

---

## 3. Completion language

```text
SCOPE COMPLETE
= one named contract/program passed its defined acceptance gates.

SYSTEM COMPLETE
= the current production system has no unresolved critical whole-system gaps
  for the explicitly declared target product state, and that exact current
  state has required current-production evidence.
```

No Human, Worker, AI agent, reviewer, automation, or report may use:

- `Workforce complete`;
- `system complete`;
- `fully autonomous`;
- `fully usable`;
- or equivalent unqualified language

solely because a historical closure such as `ACCEPTED_L10` passed.

Every completion statement MUST identify:

- scope;
- exact source/runtime identity where applicable;
- acceptance/evidence level;
- product-surface exposure where relevant;
- known exclusions/open gaps;
- whether evidence is historical or current.

---

## 4. Historical accepted baseline

```text
AUTONOMY_CLOSURE_SCOPE = ACCEPTED_L10
accepted_sha = c7d19e67797b1f97ba118433bc749bb80defe9d0
acceptance_date = 2026-09-01
```

That acceptance remains valid for its declared scope.

It MUST NOT be downgraded merely because later work exists.

It also MUST NOT be generalized into:

```text
CURRENT WHOLE WORKFORCE = COMPLETE
```

Historical acceptance and current product usability are different questions.

---

## 5. Current audited production identity

The runtime audited on 2026-09-12 was:

```text
audited_production_sha =
f7563203974ed53829fef128bbd1d1b047cd05bc
```

Production verification passed for that exact running SHA.

A later documentation-only merge moved repository `main` to:

```text
7d84ecdd470c46b9ee648de1ac47f1a833026580
```

That docs merge MUST NOT be represented as a Workforce production redeployment.

---

## 6. Audited Human → Work execution path

A previous interpretation that natural Human → durable Work admission did not exist was too broad.

The audited production implementation contains a real natural-language Work path:

```text
Human
  ↓
authorized interaction provider
  ↓
Telegram / normalized channel interaction
  ↓
/work or 🧰 Work
  ↓
ConversationSurfaceMode.WORK
  ↓
semantic interpreter
  ↓
NormalizedRequest
  ↓
if mode == EXECUTION
  ↓
executionSurfaceAuthorized = true
  ↓
ExecutionObjectiveHandoff
  ↓
HumanObjectiveIngressService
  ↓
durable ManagementObjective
  ↓
WorkQueue
  ↓
AutonomousManagementRunner.wake()
  ↓
governed Workforce management/execution
```

`MetatronConversationRuntime` explicitly calls the Intelligence responder with execution-surface authorization when the selected surface is `WORK`.

`MetatronIntelligenceResponder` admits an `EXECUTION` request when:

```text
deterministicControl
OR semanticExecutionHandoffEnabled
OR executionSurfaceAuthorized
```

Therefore:

```text
NATURAL-LANGUAGE WORK → DURABLE OBJECTIVE
= IMPLEMENTED
= PRODUCTION-COMPOSED
= EXPOSED THROUGH WORK SURFACE
```

This path MUST NOT be described as absent.

---

## 7. Chat and Work are intentionally different

Ordinary Chat intentionally does not automatically create durable Work.

Current channel composition sets:

```text
ChannelInteractionIngressService.semanticChatExecutionHandoffEnabled()
= false
```

This is a deliberate safety/product boundary.

Therefore:

```text
CHAT
= conversation / intelligence by default

WORK
= management / execution-capable surface
```

The correct gap is NOT:

```text
"Chat cannot execute, therefore Human → Work does not exist."
```

The remaining product concern is whether transition into governed Work is sufficiently discoverable, reliable and understandable without requiring the Human to know internal implementation details.

Chat safety separation MUST remain intact.

---

## 8. Current canonical Worker conversation reality

The audited Direct Worker path uses canonical Workers rather than asking an LLM to merely pretend to be a role.

`DirectWorkerConversationService` and `CanonicalWorkerConversationService` enforce/use:

- canonical Worker identity;
- ACTIVE Worker state;
- ACTIVE institutional participation;
- institutional role/position;
- runtime profile binding;
- live runtime capacity;
- Worker constitution;
- capability references;
- durable Human ↔ Worker memory;
- canonical institutional grounding;
- cognition attributed to the actual Worker ID.

The cognition request ultimately runs through Worker Intelligence with the canonical Worker as requester.

Therefore:

```text
"ALL WORKERS ARE JUST ONE AI PRETENDING TO BE MANY ROLES"
= FALSE FOR THE AUDITED CURRENT IMPLEMENTATION
```

Shared models, compute pools and runtime infrastructure do not erase durable Worker identity.

Worker identity and model/process identity are separate concepts.

---

## 9. Direct Worker conversation execution gap

Although Direct Worker conversation is real, the audited conversation path itself does not prove:

```text
Human tells selected Worker:
"do X"

→ durable Objective / Work creation
→ Assignment / task ownership
→ governed execution
→ evidence-backed completion
```

The audited conversation path currently reaches Worker cognition:

```text
Human message
  ↓
DirectWorkerConversationService
  ↓
canonical Worker validation/context/memory
  ↓
WorkerConversationGateway
  ↓
CanonicalWorkerConversationService
  ↓
WorkerIntelligenceService.reason(...)
```

It also applies `WorkerConversationExecutionClaimGuard`, which prevents a Worker from falsely claiming execution when trusted execution evidence is absent.

That guard is correct.

However, no execution claim guard substitutes for an actual work-admission path.

Current audited verdict:

```text
HUMAN ↔ CANONICAL WORKER CONVERSATION
= IMPLEMENTED / REAL

DIRECT WORKER CONVERSATION → DURABLE WORK
= NOT YET CLOSED / NOT YET PROVEN E2E
```

This is a material product gap because a canonical Worker should eventually be usable as an institutional employee, not only as a grounded conversational actor.

Target behavior:

```text
Human
  ↓
selected canonical Worker
  ↓
natural work instruction
  ↓
semantic work intent
  ↓
authority/admission
  ↓
durable Objective / Work / Assignment
  ↓
that Worker or governed delegated Workforce executes
  ↓
Observation/evidence
  ↓
result returned into the same Worker conversation
```

No parallel Objective, Assignment, Execution, Worker or authority model should be created to close this gap.

Existing canonical Workforce primitives MUST be reused.

---

## 10. Direct Coding reality

The audited Workforce source contains a private governed Direct Coding ingress:

```text
/internal/metatron/direct-coding/action
```

implemented by:

```text
DirectCodingIngressController
DirectCodingIngressService
```

The controller requires a trusted private-network request and does not expose that path as a public arbitrary mutation endpoint.

The MCP/runtime implementation also contains repository coding primitives including capabilities equivalent to:

```text
repository_open
repository_list
repository_search
repository_read
repository_patch
repository_write
repository_dependencies_install
repository_process
repository_shell
repository_git_status
repository_git_diff
repository_git_run
repository_build
repository_test
repository_pr_publish
```

The intended architecture remains:

```text
AI/Human coding ingress
  ↓
Direct Coding capability
  ↓
ExecutionAttempt
  ↓
isolated ExecutionWorkspace
  ↓
governed repository actions
  ↓
test/build
  ↓
governed PR/integration/release
```

Therefore the coding backend MUST NOT be described as nonexistent.

---

## 11. Current ChatGPT coding exposure gap

During the 2026-09-12 audit, the current ChatGPT-accessible Metatron tool schemas were rediscovered across the available Metatron connector surfaces.

The current surfaced schemas did not expose `repository_*` as directly invokable tools.

Therefore, for the audited ChatGPT product surface:

```text
DIRECT CODING BACKEND
IMPLEMENTED = YES

DIRECT CODING BACKEND
PRODUCTION/RUNTIME CODE PRESENT = YES

repository_* DIRECTLY EXPOSED TO CURRENT CHATGPT CONNECTOR
= NO / NOT PROVEN

PRODUCT-USABLE CHATGPT → repository_* CODING
= NO
```

This is an integration/exposure gap.

It MUST NOT be reported as absence of the backend implementation.

It also MUST NOT be reported as usable merely because the backend exists.

Compatibility or bounded administrative source-control paths used to perform repository changes do not prove that the canonical Direct Coding product surface is exposed.

Target:

```text
authorized AI client
  ↓
canonical repository_* capability surface
  ↓
DirectCodingIngress
  ↓
isolated governed ExecutionAttempt/workspace
  ↓
verification
  ↓
PR / integration / release according to authority
```

No second coding execution stack should be created to solve an exposure problem.

---

## 12. Current semantic cognition operational condition

At audit time, transport and core service health were live:

```text
Workforce actuator = UP
Gateway health     = OK
Telegram webhook   = configured
Telegram pending   = 0
public route probe = expected HTTP 401 for invalid secret
```

Production logs also showed real Telegram interactions reaching Workforce and returning successful responses.

However, another real interaction failed all configured semantic providers and was dead-lettered after bounded retries.

Observed provider conditions included:

```text
GOOGLE
= semantic normalization returned malformed JSON for that request

ANTHROPIC
= API rejected request because credit balance was too low

OPENAI
= API rejected request because no credits remained
```

Subsequent retry attempts were correctly bounded by the provider call-budget guard.

Therefore:

```text
TRANSPORT FAILURE = NO

CORE WORKFORCE DOWN = NO

SEMANTIC COGNITION OPERATIONALLY RELIABLE AT AUDIT TIME = NO

SEMANTIC COGNITION STATE = DEGRADED
```

Credential presence MUST NOT be treated as provider availability.

```text
API KEY PRESENT != PROVIDER USABLE
```

This is an operational condition, not evidence that the underlying Objective/autonomy engine is fake or absent.

---

## 13. Current capability/product-usability matrix

| Capability | Implementation/runtime truth | Current product truth |
|---|---|---|
| Gateway / transport | live | USABLE |
| Telegram transport | live | USABLE |
| Telegram semantic Chat | implemented | USABLE BUT CURRENTLY PROVIDER-DEGRADED |
| Chat → automatic durable execution | intentionally disabled | NOT A REQUIRED DEFAULT |
| Work natural language → durable Objective | implemented and production-composed | REAL / EXPOSED |
| Durable Objective management | implemented | REAL |
| WorkQueue + autonomous runner | implemented | REAL |
| Canonical Worker identity/runtime | implemented | REAL |
| Worker actor substrate | implemented | REAL |
| Human ↔ canonical Worker conversation | implemented | REAL |
| Durable Human↔Worker memory | implemented | REAL |
| Worker conversation → durable execution | current audited path not closed | OPEN PRODUCT GAP |
| Direct Coding backend | implemented | REAL BACKEND |
| Current ChatGPT → `repository_*` | backend exists but tool exposure absent/not proven | NOT CURRENTLY PRODUCT-USABLE |
| Semantic provider reliability | multi-provider architecture exists | DEGRADED AT AUDIT TIME |

---

## 14. Current structural gaps

### G1 — Work transition usability/discoverability

Natural-language Work → durable Objective exists.

The remaining issue is not implementation absence; it is ensuring a Human can reliably understand and enter governed Work without internal architecture knowledge.

Chat MUST remain safe/non-executing by default unless explicitly transitioned into an authorized execution surface.

### G2 — Direct Worker instruction → governed Work

A Human can talk to a real canonical Worker.

The audited path does not yet close:

```text
conversation instruction
→ durable Work
→ execution
→ evidence-backed completion
```

This is a high-priority product closure gap.

### G3 — Direct Coding client exposure

The coding backend exists.

The current ChatGPT connector/tool surface does not expose the canonical `repository_*` path.

This is a high-priority integration/exposure gap.

### G4 — Generic accountable Manager Worker topology

Current generic Objective management is coordinated by management services including:

- `HumanObjectiveIngressService`;
- `ManagementAutonomyService`;
- `AutonomousManagementRunner`;
- planning;
- scheduling;
- staffing;
- execution services.

Management-engine coordination MUST NOT be described as a separately proven canonical Manager Worker unless Worker identity, participation, ownership/Assignment and actor behavior are evidenced for that path.

### G5 — Staffing remains governed/policy-bounded

Worker formation/allocation remains constrained by approved institutional policy, capability, constitution, runtime and authority bindings.

This is correct governance.

It also means arbitrary open-ended Worker formation MUST NOT be claimed when those contracts are absent.

### G6 — Capability autonomy remains bounded

Workforce may act only through available governed capabilities and authority.

Missing capability requires replan, delegation, capability-gap handling or escalation.

It never authorizes fabricated completion.

### G7 — Recovery remains bounded

Autonomous retry/recovery/replan behavior is intentionally bounded.

```text
AUTONOMOUS
!= RETRY FOREVER
```

Exhausted recovery must escalate or reach another explicit governed terminal condition.

### G8 — Current acceptance cannot be inherited from historical acceptance

A materially changed product/runtime path must earn evidence appropriate to the changed scope.

Historical `ACCEPTED_L10` does not automatically ratify later runtime/product changes.

---

## 15. Operational conditions are distinct from structural gaps

Examples:

```text
provider has no credits
provider outage
temporary malformed provider output
host resource exhaustion
container restart storm
temporary connector cache/staleness
```

These may make a capability unavailable without meaning its architecture is absent.

Reports MUST separate:

```text
STRUCTURAL GAP
from
OPERATIONAL DEGRADATION
from
SURFACE EXPOSURE GAP
from
ACCEPTANCE/EVIDENCE GAP
```

---

## 16. Required current-state reporting format

Every future material audit/report SHALL separate:

```text
HISTORICAL ACCEPTED BASELINES

CURRENT SOURCE IDENTITY

CURRENT PRODUCTION IDENTITY

IMPLEMENTED CAPABILITIES

PRODUCTION-COMPOSED CAPABILITIES

CURRENT SURFACE EXPOSURE

CURRENT OPERATIONAL AVAILABILITY

CURRENT PRODUCT-USABILITY GAPS

CURRENT STRUCTURAL GAPS

CURRENT ACCEPTANCE / EVIDENCE LEVEL
```

A report MUST NOT collapse these into one `complete/incomplete` word.

---

## 17. Whole-system completion rule

A current whole-system `SYSTEM COMPLETE` claim is prohibited unless a current Whole-System Gap Registry proves:

```text
critical_open_gaps = 0

critical_surface_exposure_gaps = 0

critical_product_usability_gaps = 0

material_known_contradictions = 0

current_target_product_state = explicitly declared

current_source_identity = exact

current_deployed_identity = exact

required_acceptance_matrix = PASS

required_production_evidence = PASS
```

If those conditions are absent, only scope-specific completion claims are allowed.

---

## 18. Documentation consistency rule

When runtime evidence contradicts documentation:

1. preserve historical evidence;
2. do not rewrite history;
3. correct current-state documentation;
4. distinguish architecture, implementation, exposure, availability and usability;
5. record the contradiction and corrected interpretation;
6. never silently expand historical acceptance scope.

---

## 19. Non-negotiable implementation interpretation

```text
WORKER != MODEL
WORKER != RUNTIME
WORKER != EXECUTION ATTEMPT

CHAT != WORK

CONVERSATION != EXECUTION

INTENT != AUTHORITY

IMPLEMENTATION != EXPOSURE

EXPOSURE != AVAILABILITY

AVAILABILITY != PRODUCT USABILITY

EXECUTION != OUTCOME

HISTORICAL ACCEPTANCE != CURRENT ACCEPTANCE

SCOPE COMPLETE != SYSTEM COMPLETE
```

And:

```text
WORKER COUNT
!= ACTOR TURN CONCURRENCY
!= EXECUTION CONCURRENCY
!= BUILD CONCURRENCY
!= MERGE CONCURRENCY
!= DEPLOY CONCURRENCY
```

---

## 20. Immediate closure priority

Do not create new subsystems merely because the product surface is incomplete.

Current priority order is:

```text
P1
Restore reliable semantic cognition operational availability.

P2
Close Human → selected canonical Worker → durable governed Work → result.

P3
Expose the existing canonical Direct Coding capability to authorized AI clients,
starting with the currently missing ChatGPT repository_* surface.

P4
Only then reassess remaining whole-system gaps against current exact production.
```

P1–P3 should preferentially compose existing canonical primitives.

Duplicate Worker, Objective, management, coding, repository, execution or authority stacks are prohibited.

---

## 21. Current verdict

```text
AUTONOMY_CLOSURE_2026_09_01
= ACCEPTED_L10 FOR ITS DECLARED HISTORICAL SCOPE

CURRENT_WORKER_IDENTITY_AND_ACTOR_SUBSTRATE
= IMPLEMENTED / REAL

CURRENT_WORK_NATURAL_LANGUAGE_TO_DURABLE_OBJECTIVE
= IMPLEMENTED / REAL

CURRENT_AUTONOMOUS_OBJECTIVE_ENGINE
= IMPLEMENTED / REAL / GOVERNED

CURRENT_HUMAN_TO_CANONICAL_WORKER_CONVERSATION
= IMPLEMENTED / REAL

CURRENT_DIRECT_WORKER_CONVERSATION_TO_EXECUTION
= NOT CLOSED

CURRENT_DIRECT_CODING_BACKEND
= IMPLEMENTED

CURRENT_CHATGPT_DIRECT_REPOSITORY_CODING
= NOT CURRENTLY EXPOSED / NOT PRODUCT-USABLE

CURRENT_SEMANTIC_COGNITION
= OPERATIONALLY DEGRADED AT AUDIT TIME

CURRENT_WHOLE_SYSTEM_COMPLETE
= NOT CLAIMED
```

The correct current interpretation is:

> Metatron Workforce contains substantial real production-composed autonomy, Worker and execution substrate. Natural-language Work can already become durable governed Work. Canonical Workers are real institutional actors rather than provider personas. However, critical product closure remains incomplete where Human instructions to a selected Worker do not yet form a proven execution loop, where the existing Direct Coding backend is not exposed through the current ChatGPT tool surface, and where semantic-provider availability currently makes natural-language operation unreliable. These conditions must be reported precisely rather than collapsed into either “nothing works” or “the system is complete.”

---

## 22. Direct MCP client (Claude) repository write-path audit — 2026-09-16

Audited from an external Claude client connected via the Metatron v5 MCP server (`direct-mcp` ingress).

```text
repository_open / repository_list / repository_search / repository_read (inspect)
= AVAILABLE, CONFIRMED WORKING

repository_shell (raw governed shell inside isolated session)
= BLOCKED, sandbox execution HTTP 403

repository_process with executable="git", args=["status"]/["remote","-v"]
= AVAILABLE, runs, but session has no git remote configured
  (git push --dry-run origin master -> exit 128,
   "'origin' does not appear to be a git repository")

repository_patch (occurrence-checked file patch)
= AVAILABLE, CONFIRMED WORKING (this section was written via it)

repository_pr_publish (governed unmerged PR publish)
= AVAILABLE, being exercised immediately after this patch to confirm end-to-end

repository_pr_merge / any direct merge or deploy tool
= NOT PART OF DirectCodingIngressService.ACTIONS (the coding-ingress allowlist)
```

This distinction matters and must not be collapsed into a blanket "no deploy tool reachable" claim:

```text
A. Direct Coding ingress (DirectCodingIngressService.ACTIONS)
   repository inspect/patch/test/git-run/PR-publish
   = AVAILABLE

   merge / release / deploy actions
   = NOT PART OF THIS ALLOWLIST -- validateAction() throws SecurityException for them,
     enforced by a hardcoded compile-time Set.of(), covered by
     DirectCodingIngressValidationTest.

B. Separate MCP broker/Commander surface (NOT the same ingress, NOT governed by the
   allowlist above)
   workforce_deploy_local_sha
   workforce_verify_production
   production_identity
   = EXIST INDEPENDENTLY as live, callable tools

   workforce_deploy_local_sha specifically
   = CURRENTLY VISIBLE AND CALLABLE BY A DIRECT-MCP CLIENT, confirmed live during the
     2026-09-16 audit -- this is NOT protected by the coding-ingress allowlist in (A),
     because it is a different ingress entirely.

   Authorization source for surface B
   = PACKED/ENCRYPTED (broker.gz.b64, seal.gz.b64, .runtime-auth-security.enc in the
     ssh_mcp workspace) -- no readable registration source found. UNRESOLVED.
```

Conclusion: merge is genuinely not exposed anywhere in this codebase, consistent with the
invariant `workerReleaseSelfGrant=false` in coding-capability-v1.json. Deploy IS directly
reachable today by a Direct-MCP client, through surface B, and that exposure is the live,
open, unresolved security defect -- not a documentation gap to be waved away as "no
deploy tool exists." Separately, and independently of surface B: the intentional design
of surface A already matches the target architecture for coding -- raw shell/git push is
deliberately unwired (`rawRootShellCanonical=false`, `repositoryCredentialsRemainInWorkforce=true`),
and the only sanctioned Worker/direct-MCP coding write path is `repository_patch` →
`repository_pr_publish`, which does not require a local git remote because credentials
and push logic stay server-side in Workforce. No new capability was required to satisfy
that coding path for an external Claude MCP client; it already existed and is
contract-tested. Surface B's exposure is a separate, unresolved defect layered on top of
an otherwise correctly-designed surface A.
