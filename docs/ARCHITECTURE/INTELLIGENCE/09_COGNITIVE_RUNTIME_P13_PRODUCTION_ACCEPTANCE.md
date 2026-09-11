# METATRON COGNITIVE RUNTIME — P13 PRODUCTION ACCEPTANCE

**Status: ACCEPTED IN PRODUCTION — 2026-09-11**

## Accepted production candidate

```text
commit: 6c7cd1c8739e920fcb2fa0f0af7c8307b68d8847
base:   39c69dfa70f979742c0aabb71a5d58fd163cb1e4
```

The candidate contains the reconciled Cognitive Runtime on the current production base plus a fail-closed deployment preflight for the required sandbox token.

## Deployment evidence

Canonical deployment reported:

```text
BUILD SUCCESSFUL
WORKFORCE PRODUCTION DEPLOYMENT: PASS
sha=6c7cd1c8739e920fcb2fa0f0af7c8307b68d8847
```

Post-deployment runtime inspection confirmed:

```text
deploy-workforce-1
  image: metatron-workforce:6c7cd1c8739e920fcb2fa0f0af7c8307b68d8847
  status: healthy

deploy-workforce-sandbox-1
  image: metatron-workforce-sandbox:6c7cd1c8739e920fcb2fa0f0af7c8307b68d8847
  status: healthy

Workforce actuator:
  status=UP

Gateway:
  status=ok
  service=gateway
  version=v2

Telegram webhook:
  url=https://gate.metatron.vn/telegram/webhook
  pending_update_count=0
```

## Live Cognitive Runtime evidence

Production logs emitted Metatron-owned cognitive artifact records after rollout, including multiple `cognitive_artifact_saved` events from Worker cognition. This proves the deployed runtime is executing the new Cognitive Artifact path rather than merely containing dormant code.

The same production logs also showed bounded provider-unavailability failures when external provider credits/quotas were exhausted. These failures were surfaced explicitly rather than fabricated as success. Provider commercial capacity is external to Cognitive Runtime correctness and remains an operational dependency.

## Sandbox-token incident and remediation

The first P13 attempt failed before Workforce startup because the sandbox was recreated with an empty `SANDBOX_TOKEN`. Root cause was missing `METATRON_SANDBOX_TOKEN` in the production env file, not Cognitive Runtime state/schema.

Production was restored on the previous known-good SHA, the token was provisioned without exposing its value, and both sandbox and Workforce recovered healthy.

The canonical deploy script was then hardened to fail before build/container recreation when `METATRON_SANDBOX_TOKEN` is absent or blank, and to verify sandbox health as part of deployment acceptance. A regression test covers the preflight contract.

## Acceptance decision

P13 is accepted because:

- exact immutable candidate image is running;
- Workforce and sandbox are healthy;
- Gateway remains healthy;
- Telegram webhook remains configured with no pending backlog;
- production startup succeeds against current persisted Workforce state;
- live runtime emitted Cognitive Runtime artifact evidence;
- no state-schema regression or crash loop is present;
- provider capacity failures remain explicit failure states rather than false completion;
- the deployment path now fails closed on missing sandbox credentials.

This acceptance does not claim provider billing savings as a production percentage. Call-reduction claims remain limited to the machine-verifiable P12 fixtures until sufficient production telemetry exists.
