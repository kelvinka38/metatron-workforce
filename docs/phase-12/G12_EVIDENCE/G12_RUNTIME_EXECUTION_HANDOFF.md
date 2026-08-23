# METATRON WORKFORCE — G12 RUNTIME EXECUTION HANDOFF

Document Type:
Runtime Execution Handoff

Status:
READY — LOCAL EXECUTION REQUIRED

Gate:
G12 — Workforce Production Readiness

## PURPOSE

Provide the exact local execution sequence required to convert repository-side G12 readiness into attributable runtime evidence.

## EXECUTION

```powershell
$ErrorActionPreference = "Stop"
Set-Location "C:\Users\thuan\metatron-workforce"

git pull origin main
./gradlew.bat clean test --no-daemon
```

## REQUIRED CAPTURE

```powershell
$runDate = Get-Date -Format "yyyyMMdd-HHmmss"
$evidence = ".\docs\phase-12\G12_EVIDENCE\runtime\$runDate"
New-Item -ItemType Directory -Force $evidence | Out-Null

git rev-parse HEAD | Out-File "$evidence\commit-sha.txt" -Encoding utf8
git status --short | Out-File "$evidence\git-status.txt" -Encoding utf8
java -version 2> "$evidence\java-version.txt"
.\gradlew.bat --version | Out-File "$evidence\gradle-version.txt" -Encoding utf8
```

## DECISION RULE

Do not mark G12 PASS from a successful local command alone.

The runtime evidence must be reviewed against:

- `G12_EVIDENCE_COLLECTION_TRACKER.md`
- `G12_IMPLEMENTATION_GAP_REGISTER.md`
- `G12_RUNTIME_EVIDENCE_MAP.md`
- `G12_EXECUTION_CHECKLIST.md`

All HIGH gaps require attributable evidence before G12 PASS.

## HANDOFF STATE

Local execution:

PENDING

Evidence review:

PENDING

G12 decision:

PENDING
