# PRODUCTION_REQUIREMENTS.md

## Status

APPROVED FOR CURRENT CERTIFICATION ENVELOPE

## Scope

These are the minimum requirements used to certify the current deployable Workforce runtime. They are not a claim of a final internet-scale SLA.

### Functional

- preserve Worker identity independently from runtime identity;
- preserve Assignment / Authorization / Execution attribution;
- execute the primary vertical slice;
- represent multi-organization isolation;
- record runtime failure without manufacturing success;
- recover persisted runtime state after process replacement.

### Operational

- deployable JAR must start and exit cleanly after evidence capture;
- runtime state must survive JVM replacement;
- evidence must contain attributable logs, metrics, traces and audit provenance;
- evidence must identify exact commit and environment;
- production smoke must leave no unexplained failure.

### Security

- authorization decisions are attributable;
- expired/out-of-window authorization is denied;
- organization isolation is enforced by the visibility boundary;
- cross-organization resource visibility is denied.

### Reliability

- full regression suite passes;
- durable recovery acceptance passes;
- failure state remains FAILED after recovery;
- no recovery path manufactures success.

### Economic / Capacity

The certification envelope includes the existing accepted 1,000-worker representation, 8,000 available labor hours, and utilization/cost evidence from the vertical slice.

## Non-Goals

This document does not claim multi-region HA, distributed consensus, internet-scale throughput, or final cloud-provider sizing. Those require a separate workload/capacity exercise backed by production traffic evidence.
