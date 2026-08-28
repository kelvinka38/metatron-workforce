# Gateway Production Architecture — Public Workforce Publication

PUBLIC READ-ONLY DERIVATIVE — NOT SOT.

Canonical source: `kelvinka38/metatron-institution/06_GATEWAY/GATEWAY_PRODUCTION_ARCHITECTURE.md` at commit `b3516179879dc90dd482660efef71894069470c3`, blob `6df7b66bfa6a27f2f42425dee1da2342710f3f6d`.

## Architecture baseline

Metatron production runtime is online-first and must operate independently of a user's workstation. Local runtime is development/validation only and is not a production dependency.

The accepted data-plane technology in the source baseline is Envoy Proxy. Hosting provider, orchestrator, production identity provider, secrets vendor, DNS provider and observability vendor remain deployment decisions unless separately governed.

## Logical topology

`INTERNET / EXTERNAL ACTORS → managed DNS/TLS edge → Gateway boundary / Envoy → private network → Metatron BIOS/runtime → Workforce → capability services/workers`.

A separate control/operations plane owns secrets, configuration, deployment, monitoring, logs, certificates, backup, recovery and audit evidence.

## Gateway-owned concerns

External network boundary, transport security, identity ingress integration, authentication enforcement, explicitly assigned authorization enforcement, structural validation, admission, routing, egress policy, provenance/correlation, boundary observability and boundary failure handling.

Gateway does not absorb business-domain policy, worker execution decisions, business workflows or product/domain semantics.

## Availability

The source architecture baseline calls for multiple Gateway instances so a single instance failure does not require workstation intervention. Security-sensitive failure modes fail closed. Dependency failures return bounded, attributable errors rather than false success.

## Production independence

Production continuity must not require a user's Windows machine, Docker Desktop, localhost service, local checkout, local `.env`, local database, local identity service or local tunnel. Production configuration and secrets belong in continuously available online infrastructure.
