# Gateway G6 Production Completion — Public Workforce Publication

PUBLIC READ-ONLY DERIVATIVE — NOT SOT.

Canonical source: `kelvinka38/metatron-institution/06_GATEWAY/g6/online/PRODUCTION_COMPLETION.md` at commit `b3516179879dc90dd482660efef71894069470c3`, blob `5edd617eafc65e1476a91095d0c71159a7e5a05a`.

## Runtime contract

Public ingress is Cloudflare Tunnel only. Application ports on the production hosts remain internal to the Gateway network. Gateway authentication/authorization enforcement fails closed. Declared upstreams include BIOS, Workforce, Capability and Authorization; requests may not introduce arbitrary upstream hosts.

## Deployment inputs

Production deployment requires explicit tunnel, hostname, credential, authorization, Workforce and Capability endpoint inputs. Secrets are deployment inputs and are not repository configuration.

## Egress boundary

Gateway containers do not expose arbitrary public application ports and do not provide an unrestricted destination proxy. Outbound access from downstream capabilities must use the applicable controlled egress boundary.

## High availability

The completion design calls for the same immutable release on multiple production hosts, with independent Gateway/Tunnel connectors where deployed. Loss of one host must not require changing surviving application configuration.

## Recovery

Detect unhealthy host/connector, remove it from service, preserve last known-good release, re-provision, restore governed secrets, start immutable release, verify local health and edge-to-Gateway health, then reintroduce only after smoke checks pass.

Rollback is release-level. Production containers are not hot-edited into a new canonical state.

## Completion boundary

Repository code/config completion and runtime acceptance are distinct. Runtime acceptance requires actual production deployment evidence and upstream availability; customer/market validation is not a Gateway infrastructure completion prerequisite.
