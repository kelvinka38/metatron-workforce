# CAPACITY_MODEL.md

## Status

APPROVED FOR CURRENT CERTIFICATION ENVELOPE

## Verified Envelope

| Dimension | Verified value | Interpretation |
|---|---:|---|
| Representable workers | 1,000 | acceptance representation |
| Concurrent workflows | 64 | acceptance concurrency |
| Available labor hours | 8,000 | capacity scenario |
| Required labor hours | 8,000 | no deficit in scenario |
| Capacity deficit | 0 hours | scenario result |
| Utilization | 1.0 | scenario utilization |

## Rule

These values are evidence for the current acceptance envelope. They are not a final cloud-sizing recommendation.

Infrastructure scale-out decisions require measured workload data and must not alter Workforce institutional semantics.

## Runtime Persistence

Current certification uses an atomic filesystem persistence boundary for the deployable single-node runtime. The persistence abstraction is replaceable when workload/capacity evidence justifies a distributed store.
