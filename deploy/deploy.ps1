param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$CommitSha
)

$ErrorActionPreference = "Stop"

throw @"
deploy/deploy.ps1 is retired as a production mutation path.
Production deployment is Linux-hosted and owned exclusively by the persistent Highway control plane.
Use deploy/deploy-production-sha.sh <40-char-commit-sha> from the governed production release path.
Requested SHA: $CommitSha
"@
