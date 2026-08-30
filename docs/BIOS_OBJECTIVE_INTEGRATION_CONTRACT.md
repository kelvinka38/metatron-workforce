# METATRON WORKFORCE — BIOS OBJECTIVE INTEGRATION CONTRACT

**Status:** APPROVED INTEGRATION BOUNDARY  
**Consumer:** BIOS Product #1  
**Authority:** Workforce canonical management SOT + BIOS SOT v2.0  
**Founder Approval:** 2026-08-31

---

## CURRENT IMPLEMENTATION CLAIM BOUNDARY

This approved contract defines the target boundary; contract approval is not live end-to-end acceptance. Current production evidence proves BIOS independently and proves bounded Workforce paths, but does not yet prove the live BIOS service -> durable Workforce Objective -> verified Outcome path. Report that path as `CONTRACT APPROVED / LIVE END-TO-END NOT YET ACCEPTED` until the Autonomy Closure production gate passes.

---

# 1. PURPOSE

This document records how BIOS Product #1 consumes Workforce without redefining Workforce semantics.

BIOS produces or references durable Objective context from an accepted Program. Workforce remains authoritative for management ownership, organization, staffing, assignment, coordination, review, recovery, replanning, reassignment, and escalation of work.

---

# 2. CANONICAL HANDOFF

```text
BIOS CASE
→ BIOS PROGRAM
→ OBJECTIVE CONTRACT
→ WORKFORCE MANAGEMENT
→ AUTHORIZATION
→ EXECUTION
→ OBSERVATION / EVIDENCE
→ BIOS OUTCOME / STATE UPDATE
```

---

# 3. BIOS PROVIDES

Where applicable, BIOS should provide/reference:

```text
objective_id
case_id
program_id
desired_outcome
scope
constraints
priority
deadline
budget/resource envelope
risk tolerance
authority requirements
approval points
acceptance criteria
evidence requirements
exit conditions
```

BIOS does not create Worker identity or unlimited authority by creating an Objective.

---

# 4. WORKFORCE OWNS

Workforce remains responsible for canonical management semantics, including as applicable:

```text
understand context
plan
assess capability / capacity / resources
organize
staff
assign / delegate
schedule
coordinate
resolve/request authorization
hand off to Execution
observe outcome/evidence
review
recover / retry / adapt / replan / reassign
escalate when required
report
learn for future work
```

---

# 5. INVARIANTS

```text
BIOS PROGRAM ≠ WORKFORCE AUTHORIZATION
OBJECTIVE ≠ ASSIGNMENT
ASSIGNMENT ≠ EXECUTION REQUEST
WORKER ≠ RUNTIME
INTELLIGENCE PROVIDER ≠ WORKER BY DEFAULT
EXECUTION SUCCESS ≠ OBJECTIVE SUCCESS
```

Objective lifetime must not depend on the originating chat/model call.

---

# 6. MINIMUM SUFFICIENT WORKFORCE

Staffing must be driven by required capability/capacity and the Objective's Quality/Time/Cost/Risk constraints.

Arbitrary Worker count or agent count is not an acceptance criterion.

---

# 7. HUMAN ESCALATION

Workforce should not require routine Human micromanagement for work that is within delegated authority.

Escalate when authority, risk, resource, ambiguity, policy, or required Human judgment makes escalation legitimate.

Routine recoverable failure should be handled through the canonical Workforce recovery loop where authorized.

---

# 8. COMPLETION

Workforce reports execution/work state through authoritative contracts. Objective completion must be closed against required acceptance criteria and Observation/evidence.

BIOS consumes the resulting Outcome to update Case State and Learning.

---

# 9. TECHNICAL ACCEPTANCE

The BIOS↔Workforce integration is technically acceptable when a durable BIOS Objective can:

1. be admitted to Workforce;
2. obtain valid management ownership;
3. be decomposed/organized where required;
4. be staffed according to capability/capacity;
5. preserve authorization boundaries;
6. execute through the authoritative Execution boundary;
7. survive ordinary chat/session/runtime replacement;
8. recover from bounded routine failure where required by the test case;
9. produce observable evidence/Outcome;
10. return sufficient Outcome context for BIOS State/Learning update.

Real external customer adoption is not required for this technical integration acceptance.

**APPROVED — 2026-08-31**