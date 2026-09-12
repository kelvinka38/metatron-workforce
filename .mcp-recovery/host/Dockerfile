# CLIENT_AGNOSTIC_POST_CANONICAL_V2
# CLIENT_AGNOSTIC_MCP_CANONICAL_V1
FROM metatron-ssh-mcp:next AS current-helper

FROM node:20-slim AS git-acceptance
RUN apt-get update \
 && apt-get install -y --no-install-recommends git python3 ca-certificates gzip openssh-client \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /test
RUN npm init -y && npm install --omit=dev @modelcontextprotocol/sdk@latest
COPY server-final.gz.b64 /test/server.gz.b64
COPY broker.gz.b64 /test/broker.gz.b64
COPY seal.gz.b64 /test/seal.gz.b64
RUN base64 -d /test/server.gz.b64 | gzip -d > /test/server.mjs \
 && base64 -d /test/broker.gz.b64 | gzip -d > /test/host_capability_broker.py \
 && base64 -d /test/seal.gz.b64 | gzip -d > /test/seal_root_key.py
COPY direct-coding-patch.mjs /test/direct-coding-patch.mjs
COPY repository-discovery-patch.mjs /test/repository-discovery-patch.mjs
COPY patch-request-guard.mjs /test/patch-request-guard.mjs
COPY auth-proxy.mjs /test/auth-proxy.mjs
COPY auth-professional-patch.mjs /test/auth-professional-patch.mjs
COPY auth-security-admin-patch.mjs /test/auth-security-admin-patch.mjs
COPY auth-security-admin-fix.mjs /test/auth-security-admin-fix.mjs
COPY auth-security-recovery-patch.mjs /test/auth-security-recovery-patch.mjs
COPY auth-control-state-host-patch.mjs /test/auth-control-state-host-patch.mjs
COPY auth-control-state-init-patch.mjs /test/auth-control-state-init-patch.mjs
# CANONICAL_CHEAP_READINESS_V1
COPY auth-readiness-cheap-hotfix.mjs /test/auth-readiness-cheap-hotfix.mjs
COPY auth-client-agnostic-oauth-patch.mjs /test/auth-client-agnostic-oauth-patch.mjs
COPY auth-client-agnostic-post-patch.mjs /test/auth-client-agnostic-post-patch.mjs
COPY server-client-agnostic-auth-guard-patch.mjs /test/server-client-agnostic-auth-guard-patch.mjs
COPY request-guard-acceptance.mjs /test/request-guard-acceptance.mjs
COPY --from=current-helper /app/bounded_git_helper.py /test/bounded_git_helper.py
COPY bounded_git_github_patch.py /test/bounded_git_github_patch.py
COPY bounded_git_post_patch.py /test/bounded_git_post_patch.py
COPY bounded_git_diag_patch.py /test/bounded_git_diag_patch.py
RUN python3 /test/bounded_git_github_patch.py /test/bounded_git_helper.py \
 && python3 /test/bounded_git_post_patch.py /test/bounded_git_helper.py \
 && python3 /test/bounded_git_diag_patch.py /test/bounded_git_helper.py
COPY bounded_git_acceptance_test.py /test/bounded_git_acceptance_test.py
COPY bounded_git_github_acceptance_test.py /test/bounded_git_github_acceptance_test.py
COPY registry_acceptance_test.mjs /test/registry_acceptance_test.mjs
COPY coding-capability-v1.json /test/coding-capability-v1.json
COPY live_mcp_acceptance.py /test/live_mcp_acceptance.py
RUN mkdir -p /app \
 && cp /test/server.mjs /app/server.mjs \
 && cp /test/direct-coding-patch.mjs /app/direct-coding-patch.mjs \
 && node /app/direct-coding-patch.mjs \
 && node /test/repository-discovery-patch.mjs /app/server.mjs \
 && cp /test/patch-request-guard.mjs /app/patch-request-guard.mjs \
 && node /app/patch-request-guard.mjs \
 && cp /app/server.mjs /test/server.mjs \
 && node /test/auth-professional-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-security-admin-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-security-admin-fix.mjs /test/auth-proxy.mjs \
 && node /test/auth-security-recovery-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-control-state-host-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-control-state-init-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-readiness-cheap-hotfix.mjs /test/auth-proxy.mjs \
 && node /test/auth-client-agnostic-oauth-patch.mjs /test/auth-proxy.mjs \
 && node /test/auth-client-agnostic-post-patch.mjs /test/auth-proxy.mjs \
 && node /test/server-client-agnostic-auth-guard-patch.mjs /test/server.mjs \
 && node --check /test/server.mjs \
 && node --check /test/auth-proxy.mjs \
 && node --check /test/request-guard-acceptance.mjs \
 && AUTH_PROXY_SELF_TEST=1 node /test/auth-proxy.mjs \
 && node /test/request-guard-acceptance.mjs \
 && node /test/registry_acceptance_test.mjs \
 && python3 -m py_compile /test/host_capability_broker.py /test/seal_root_key.py /test/bounded_git_helper.py /test/bounded_git_acceptance_test.py /test/bounded_git_github_acceptance_test.py /test/live_mcp_acceptance.py \
 && python3 /test/bounded_git_acceptance_test.py \
 && python3 /test/bounded_git_github_acceptance_test.py \
 && python3 /test/live_mcp_acceptance.py https://ssh.metatron.vn/mcp \
 && grep -q 'metatron-mcp@host.docker.internal' /test/server.mjs \
 && ! grep -q 'root@host.docker.internal' /test/server.mjs \
 && ! grep -q 'ROOT_SSH' /test/server.mjs \
 && grep -q 'TRUSTED_OPENAI_ORG_HASHES' /test/server.mjs \
 && grep -q 'metatron/verifiedClient' /test/server.mjs \
 && grep -q 'metatron/proxyAssertion' /test/server.mjs \
 && grep -q 'METATRON_PROXY_ASSERTION' /test/server.mjs \
 && grep -q 'repository_open' /test/server.mjs \
 && grep -q 'repository_access' /test/server.mjs \
 && grep -q 'METATRON_REPOSITORY_DISCOVERY_V1' /test/server.mjs \
 && grep -q 'githubConnectorRequired:false' /test/server.mjs \
 && grep -q 'repository_pr_publish' /test/server.mjs \
 && grep -q 'workforce-production:8080/internal/metatron/direct-coding/action' /test/server.mjs \
 && grep -q 'METATRON_SECURE_AUTH_UI_V2' /test/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_PASSWORD_SCRYPT' /test/auth-proxy.mjs \
 && grep -q 'verifyFounderSecret' /test/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_SECURITY_ADMIN_V1' /test/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_SECURITY_RECOVERY_V1' /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_V1' /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_INIT_V2' /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2' /test/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_OAUTH_V1' /test/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2' /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_PROTOCOL_TRANSPARENT_V1' /test/auth-proxy.mjs \
 && grep -q 'METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1' /test/auth-proxy.mjs \
 && grep -q 'CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS' /test/auth-proxy.mjs \
 && grep -q 'metatron/authenticatedPrincipal' /test/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_PROXY_GUARD_V1' /test/server.mjs \
 && ! grep -Fq '["gemini","claude"].includes(request?.params?._meta?.["metatron/verifiedClient"])' /test/server.mjs \
 && grep -q 'AUTH_CONTROL_STATE_HOST_SAVE_PASS' /test/auth-proxy.mjs \
 && grep -q 'AUTH_CONTROL_STATE_HOST_INITIALIZED' /test/auth-proxy.mjs \
 && grep -q 'AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE' /test/auth-proxy.mjs \
 && grep -q "url.pathname === '/ready'" /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CHEAP_READINESS_V2' /test/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1' /test/auth-proxy.mjs \
 && ! grep -q "body.includes('server_status') && body.includes('repository_open')" /test/auth-proxy.mjs \
 && grep -q 'sha256(candidate.trim())' /test/auth-proxy.mjs \
 && grep -q '/founder/security/revoke-client' /test/auth-proxy.mjs \
 && grep -q 'accessTokenAllowed' /test/auth-proxy.mjs \
 && grep -q 'SameSite=Strict' /test/auth-proxy.mjs \
 && printf 'PRIVSEP_FINAL_STATIC_ACCEPTANCE_PASS\n' \
 && printf 'MCP_ACCEPTANCE_PASS\n' > /test/ACCEPTANCE_PASS

FROM node:20-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-client ca-certificates gzip python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN npm init -y \
 && npm install --omit=dev @modelcontextprotocol/sdk@latest supergateway@3.4.3
COPY supergateway-lifecycle-fix-v1.mjs /tmp/supergateway-lifecycle-fix-v1.mjs
RUN node /tmp/supergateway-lifecycle-fix-v1.mjs /app/node_modules/supergateway/dist/gateways/stdioToStatelessStreamableHttp.js \
 && node --check /app/node_modules/supergateway/dist/gateways/stdioToStatelessStreamableHttp.js \
 && grep -Fq 'METATRON_STATELESS_CHILD_REAPER_V1' /app/node_modules/supergateway/dist/gateways/stdioToStatelessStreamableHttp.js \
 && rm -f /tmp/supergateway-lifecycle-fix-v1.mjs
COPY server-final.gz.b64 /tmp/server.gz.b64
RUN base64 -d /tmp/server.gz.b64 | gzip -d > /app/server.mjs \
 && rm -f /tmp/server.gz.b64
COPY direct-coding-patch.mjs /app/direct-coding-patch.mjs
RUN node /app/direct-coding-patch.mjs \
 && rm /app/direct-coding-patch.mjs
COPY repository-discovery-patch.mjs /app/repository-discovery-patch.mjs
RUN node /app/repository-discovery-patch.mjs /app/server.mjs \
 && rm /app/repository-discovery-patch.mjs
COPY patch-request-guard.mjs /app/patch-request-guard.mjs
RUN node /app/patch-request-guard.mjs \
 && rm /app/patch-request-guard.mjs
COPY auth-proxy.mjs /app/auth-proxy.mjs
COPY auth-professional-patch.mjs /app/auth-professional-patch.mjs
COPY auth-security-admin-patch.mjs /app/auth-security-admin-patch.mjs
COPY auth-security-admin-fix.mjs /app/auth-security-admin-fix.mjs
COPY auth-security-recovery-patch.mjs /app/auth-security-recovery-patch.mjs
COPY auth-control-state-host-patch.mjs /app/auth-control-state-host-patch.mjs
COPY auth-control-state-init-patch.mjs /app/auth-control-state-init-patch.mjs
COPY auth-readiness-cheap-hotfix.mjs /app/auth-readiness-cheap-hotfix.mjs
COPY auth-client-agnostic-oauth-patch.mjs /app/auth-client-agnostic-oauth-patch.mjs
COPY auth-client-agnostic-post-patch.mjs /app/auth-client-agnostic-post-patch.mjs
COPY server-client-agnostic-auth-guard-patch.mjs /app/server-client-agnostic-auth-guard-patch.mjs
RUN node /app/auth-professional-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-security-admin-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-security-admin-fix.mjs /app/auth-proxy.mjs \
 && node /app/auth-security-recovery-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-control-state-host-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-control-state-init-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-readiness-cheap-hotfix.mjs /app/auth-proxy.mjs \
 && node /app/auth-client-agnostic-oauth-patch.mjs /app/auth-proxy.mjs \
 && node /app/auth-client-agnostic-post-patch.mjs /app/auth-proxy.mjs \
 && node /app/server-client-agnostic-auth-guard-patch.mjs /app/server.mjs \
 && rm /app/auth-professional-patch.mjs /app/auth-security-admin-patch.mjs /app/auth-security-admin-fix.mjs /app/auth-security-recovery-patch.mjs /app/auth-control-state-host-patch.mjs /app/auth-control-state-init-patch.mjs /app/auth-readiness-cheap-hotfix.mjs
# HOST_MAINTENANCE_TOOLS_V1_IMAGE_PATCH
COPY host-maintenance-tools-v1.mjs /app/host-maintenance-tools-v1.mjs
RUN node /app/host-maintenance-tools-v1.mjs && rm /app/host-maintenance-tools-v1.mjs
COPY mcp-entrypoint.sh /app/mcp-entrypoint.sh
RUN chmod 755 /app/mcp-entrypoint.sh \
 && mkdir -p /var/lib/metatron-auth \
 && chmod 700 /var/lib/metatron-auth
ENV METATRON_AUTH_SECURITY_STATE_PATH=/var/lib/metatron-auth/security.json
COPY --from=git-acceptance /test/bounded_git_helper.py /app/bounded_git_helper.py
COPY --from=git-acceptance /test/ACCEPTANCE_PASS /app/MCP_ACCEPTANCE_PASS
RUN node --check /app/server.mjs \
 && node --check /app/auth-proxy.mjs \
 && python3 -m py_compile /app/bounded_git_helper.py \
 && ! grep -q 'root@host.docker.internal' /app/server.mjs \
 && ! grep -q 'ROOT_SSH' /app/server.mjs \
 && grep -q 'metatron/verifiedClient' /app/server.mjs \
 && grep -q 'metatron/proxyAssertion' /app/server.mjs \
 && grep -q 'METATRON_PROXY_ASSERTION' /app/server.mjs \
 && grep -q 'repository_open' /app/server.mjs \
 && grep -q 'repository_access' /app/server.mjs \
 && grep -q 'METATRON_REPOSITORY_DISCOVERY_V1' /app/server.mjs \
 && grep -q 'repository_pr_publish' /app/server.mjs \
 && grep -q 'METATRON_SECURE_AUTH_UI_V2' /app/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_PASSWORD_SCRYPT' /app/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_SECURITY_ADMIN_V1' /app/auth-proxy.mjs \
 && grep -q 'METATRON_FOUNDER_SECURITY_RECOVERY_V1' /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_V1' /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_INIT_V2' /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2' /app/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_OAUTH_V1' /app/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2' /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_PROTOCOL_TRANSPARENT_V1' /app/auth-proxy.mjs \
 && grep -q 'METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1' /app/auth-proxy.mjs \
 && grep -q 'CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS' /app/auth-proxy.mjs \
 && grep -q 'metatron/authenticatedPrincipal' /app/auth-proxy.mjs \
 && grep -q 'METATRON_CLIENT_AGNOSTIC_PROXY_GUARD_V1' /app/server.mjs \
 && ! grep -Fq '["gemini","claude"].includes(request?.params?._meta?.["metatron/verifiedClient"])' /app/server.mjs \
 && grep -q 'Encrypted host-broker control state' /app/auth-proxy.mjs \
 && grep -q "url.pathname === '/ready'" /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CHEAP_READINESS_V2' /app/auth-proxy.mjs \
 && grep -q 'METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1' /app/auth-proxy.mjs \
 && ! grep -q "body.includes('server_status') && body.includes('repository_open')" /app/auth-proxy.mjs \
 && grep -q 'MCP_PROXY_ASSERTION_SOURCE=stable-transport-key' /app/mcp-entrypoint.sh \
 && grep -q 'MCP_CRITICAL_CHILD_EXIT component=auth-proxy' /app/mcp-entrypoint.sh \
 && grep -qx 'MCP_ACCEPTANCE_PASS' /app/MCP_ACCEPTANCE_PASS
VOLUME ["/var/lib/metatron-auth"]
HEALTHCHECK --interval=30s --timeout=6s --start-period=15s --retries=3 CMD node -e "fetch('http://127.0.0.1:3002/ready',{signal:AbortSignal.timeout(5000)}).then(async r=>{if(!r.ok)process.exit(1);const j=await r.json();if(j.status!=='READY')process.exit(1)}).catch(()=>process.exit(1))"
ENTRYPOINT ["/app/mcp-entrypoint.sh"]
