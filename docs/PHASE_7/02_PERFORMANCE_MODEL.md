# METATRON WORKFORCE — PHASE 7 / 02 PERFORMANCE MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 7 — Reporting / Performance / Economic Evidence

## 1. Purpose

Represent operational performance without reducing institutional performance to a single opaque score.

## 2. Required dimensions

Performance preserves distinct measures for:

- planned;
- committed;
- executed;
- completed;
- quality;
- timeliness;
- cost;
- resource efficiency;
- outcome.

## 3. Ratios

For compatible non-zero planned quantities:

```text
Completion Ratio = Completed / Planned
Execution Ratio  = Executed / Planned
```

A zero planned quantity is not silently converted into a divide-by-zero result; the implementation uses a neutral ratio of `1.0` for the corresponding derived metric.

## 4. Variance

The canonical variance definition is:

```text
Variance = Actual − Planned
```

Examples include:

- labor variance;
- cost variance;
- capacity variance;
- schedule variance;
- output variance;
- quality variance.

A positive or negative variance is evidence requiring interpretation; Workforce does not automatically declare the variance good or bad without the applicable metric semantics.

## 5. Historical truth

A later recalculation must not overwrite the historical performance observation from which an earlier management decision was made.

## 6. Boundary

Performance measures are operational evidence. Workforce does not become the authoritative accounting domain, governance domain, or external observation authority.
