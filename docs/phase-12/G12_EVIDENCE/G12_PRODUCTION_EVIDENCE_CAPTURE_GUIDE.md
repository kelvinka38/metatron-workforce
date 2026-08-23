# METATRON WORKFORCE — G12 PRODUCTION EVIDENCE CAPTURE GUIDE

Document Type:
Production Evidence Collection Contract

Gate:
G12 — Workforce Production Readiness

Status:
Ready for Deployment Evidence Collection

## 1. PURPOSE

Provide the exact production-side evidence required to close the remaining G12 HIGH gaps.

CI acceptance is already passing. It is not sufficient to claim production readiness. The production evidence must come from an actually deployed Workforce runtime and must be attributable to the deployed version.

## 2. REQUIRED PRODUCTION EVIDENCE

### 2.1 Runtime Identity

Capture:

- deployed commit SHA
- deployment version or release identifier
- environment identifier
- runtime instance identifier(s)
- deployment timestamp

Acceptance:

- every production evidence record can be tied to the deployed version
- environment identity is explicit

### 2.2 Runtime Health

Capture:

- runtime instance count
- READY / RUNNING / FAILED / TERMINATED counts
- runtime replacement events
- runtime continuity records
- health/readiness signal

### 2.3 Execution Health

Capture:

- execution count
- success count
- failure count
- blocked count
- execution duration
- recovery count
- execution/recovery timestamps

### 2.4 Capacity / Utilization

Capture:

- worker capacity
- available labor-hours
- required labor-hours
- capacity deficit
- utilization ratio
- concurrent workflow count

### 2.5 Security

Capture attributable production records for:

- identity
- authentication boundary
- authorization decision
- DENY decision
- organization context
- data visibility enforcement
- delegation reference
- revocation result

### 2.6 Audit / Provenance

Every captured record must contain, where applicable:

- execution ID or runtime ID
- worker ID
- organization ID/context
- timestamp
- source event
- deployed commit SHA/version
- environment identity

## 3. MINIMUM DEMONSTRATION

A production evidence run must demonstrate at least:

1. one valid authorized execution;
2. one denied/blocked unauthorized execution;
3. one organization-boundary check;
4. one runtime health observation;
5. one execution completion record;
6. one capacity/utilization observation;
7. one recovery/failure observation if the deployed runtime supports recovery;
8. provenance linking the records to the deployed version.

## 4. EVIDENCE PACKAGE

Store the resulting evidence under:

`docs/phase-12/G12_EVIDENCE/runtime/production/<timestamp>/`

Minimum files:

- `deployment-identity.txt`
- `runtime-health.json`
- `execution-summary.json`
- `capacity-utilization.json`
- `security-decisions.json`
- `audit-provenance.json`
- `README.md`

Do not fabricate these files. They must be generated from the deployed runtime or its authoritative observability system.

## 5. CLOSURE RULE

The G12 Operations gap may be closed only when production evidence exists and is attributable.

The G12 Security gap may be closed only when production identity, authorization, organization isolation, and data-visibility evidence exists and is attributable.

Only after both HIGH gaps are closed may the G12 Decision Record move from PENDING to PASS/CONDITIONAL PASS.
