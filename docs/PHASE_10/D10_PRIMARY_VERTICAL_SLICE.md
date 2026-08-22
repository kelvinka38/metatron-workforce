# PHASE 10 — PRIMARY VERTICAL SLICE

## Objective

Prove the Workforce architecture end-to-end through one realistic organization:

Human → Head of Workforce → Head of Farm → Farm Workers.

The slice is deliberately deterministic and domain-semantic. It does not implement external Governance, Economy accounting, Gateway enforcement, or execution infrastructure.

## Required flow

1. Human issues a natural-language operating request.
2. Head of Workforce assigns responsibility to Head of Farm.
3. Head of Farm analyzes workload, staffing, capacity, resources, cost, and risk.
4. Head of Farm produces a farm operating plan.
5. An explicit authority reference approves the plan.
6. Farm Workers execute only within authorized time, capacity, and resource bounds.
7. Farm Head reports actuals, variance, cost, performance, and issues.
8. Human reviews the result and can approve, reject, question, modify, or reassign.
9. A learning cycle identifies prediction error, root cause, lesson, and improvement.
10. A validated improvement is applied to the next planning cycle.

## Invariants

- No execution without explicit approval.
- No execution beyond available capacity.
- Failed execution remains failed.
- Cost is evidence, not accounting truth.
- Every material stage preserves provenance.
- Learning does not silently become institutional knowledge.
- Improvement must be validated before affecting the next cycle.
- Authority does not expand through the slice.

## Gate G10

D10 passes only when the complete slice works end-to-end without bypassing institutional semantics and the full test suite passes.
