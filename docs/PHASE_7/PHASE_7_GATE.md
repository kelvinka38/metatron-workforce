# METATRON WORKFORCE — PHASE 7 / GATE G7

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 7 — Reporting / Performance / Economic Evidence
**Status:** Gate evidence definition

## 1. Gate objective

G7 determines whether Workforce provides a truthful management/reporting layer over operational evidence while preserving performance semantics, economic evidence, attribution, historical truth, and external-domain boundaries.

## 2. Required evidence

### Reporting

- [ ] Worker reports are typed and attributable.
- [ ] Daily, periodic, task, exception, incident, performance, and economic reports are representable.
- [ ] Current, completed, and blocked work are visible.
- [ ] Capacity and staffing are visible.
- [ ] Risks and exceptions are visible.
- [ ] Human management actions remain connected to existing authority/authorization semantics.

### Performance

- [ ] Planned quantity is represented.
- [ ] Committed quantity is represented.
- [ ] Executed quantity is represented.
- [ ] Completed quantity is represented.
- [ ] Quality is represented.
- [ ] Timeliness is represented.
- [ ] Cost is represented.
- [ ] Resource efficiency is represented.
- [ ] Outcome is represented.
- [ ] Completion/execution ratios are derivable.
- [ ] Variance uses `Actual − Planned`.
- [ ] Historical performance evidence remains reconstructable.

### Economic evidence

- [ ] Planned cost evidence is preserved.
- [ ] Actual cost evidence is preserved.
- [ ] Worker utilization evidence is representable.
- [ ] Resource usage evidence is representable.
- [ ] Allocation basis is explicit.
- [ ] Cost variance is derivable.
- [ ] P&L-compatible operational evidence can be produced.
- [ ] Workforce does not claim authoritative accounting ownership.

### Boundaries

- [ ] Reports remain evidence, not automatically institutional truth.
- [ ] Inference remains distinguishable from fact.
- [ ] Budget visibility does not manufacture budget authority.
- [ ] Reporting does not manufacture authorization.
- [ ] Dashboard recalculation does not rewrite historical evidence.
- [ ] Attribution and temporal context remain preserved.

## 3. Core acceptance scenario

A realistic management scenario MUST demonstrate:

```text
Execution / Work Evidence
        ↓
Worker Report
        ↓
Performance Measures
        ↓
Variance
        ↓
Economic Evidence
        ↓
Management Dashboard
        ↓
Human Review / Decision
```

The management view must expose the evidence necessary to answer:

- What is happening?
- Who is doing it?
- Who is responsible?
- What is blocked?
- How much capacity remains?
- How much did it cost?
- Did we meet target?
- Why did we miss?
- What should happen next?

## 4. Mandatory variance scenario

Example:

```text
Planned labor = 100 hours
Actual labor  = 120 hours
Labor variance = +20 hours
```

The implementation MUST preserve the `+20` variance and MUST NOT silently rewrite planned labor as actual labor.

## 5. Mandatory economic-boundary scenario

Example:

```text
Planned cost = 1,000
Actual cost  = 1,200
Cost variance = +200
```

Workforce may expose this as operational economic evidence. It MUST NOT claim that the value is authoritative accounting truth.

## 6. Mandatory blocked-management scenario

When blocked work exists, the management view MUST retain the blocked work and relevant risk/exception evidence rather than reporting the workload as completed.

## 7. Historical boundary

Later reports, corrections, retries, or recalculation MUST NOT erase the historical evidence used for an earlier report or management decision.

## 8. Failure rule

If reporting, performance, economic evidence, attribution, or historical evidence is missing or contradictory, G7 MUST NOT be treated as passed.

## 9. Exit condition

G7 is complete only when:

- [ ] Reporting model is implemented and tested.
- [ ] Management dashboard/read model is implemented and tested.
- [ ] Performance model is implemented and tested.
- [ ] Variance semantics are implemented and tested.
- [ ] Economic evidence model is implemented and tested.
- [ ] Core management acceptance scenario is tested.
- [ ] Historical boundary is tested.
- [ ] Workforce/Economy boundary is preserved.
- [ ] Repository tests pass reproducibly.

Only after these conditions are satisfied may execution advance to Phase 8 — Learning / Self-Improvement.
