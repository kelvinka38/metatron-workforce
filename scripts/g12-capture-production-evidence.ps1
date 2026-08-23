$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$sha = (git rev-parse HEAD).Trim()
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$evidenceRoot = ".\docs\phase-12\G12_EVIDENCE\runtime\production\$timestamp"

New-Item -ItemType Directory -Force $evidenceRoot | Out-Null

Write-Host "=== BUILD DEPLOYABLE RUNTIME ===" -ForegroundColor Cyan
& .\gradlew.bat clean bootJar --no-daemon
if ($LASTEXITCODE -ne 0) { throw "BOOT JAR BUILD FAILED: $LASTEXITCODE" }

$env:METATRON_COMMIT_SHA = $sha
$env:METATRON_VERSION = "local-$sha"
$env:METATRON_ENVIRONMENT = "local-production-evidence"

Write-Host "=== EXECUTE DEPLOYABLE RUNTIME ===" -ForegroundColor Cyan
& java -jar ".\build\libs\metatron-workforce-0.1.0.jar"
if ($LASTEXITCODE -ne 0) { throw "DEPLOYABLE RUNTIME FAILED: $LASTEXITCODE" }

$generated = Get-ChildItem ".\runtime-evidence\production" -Recurse -File | Sort-Object LastWriteTime
if (-not $generated) { throw "NO PRODUCTION RUNTIME EVIDENCE GENERATED" }

Copy-Item ".\runtime-evidence\production\*" $evidenceRoot -Recurse -Force

@"
commit=$sha
version=$env:METATRON_VERSION
environment=$env:METATRON_ENVIRONMENT
capturedAt=$([DateTime]::UtcNow.ToString("o"))
source=deployed-local-JVM-runtime
jar=build/libs/metatron-workforce-0.1.0.jar
"@ | Set-Content "$evidenceRoot\deployment-command.txt" -Encoding utf8

Write-Host "=== PRODUCTION EVIDENCE GENERATED ===" -ForegroundColor Green
Get-ChildItem $evidenceRoot -Recurse -File | Select-Object FullName,Length

Write-Host "=== DO NOT COMMIT EVIDENCE YET ===" -ForegroundColor Yellow
Write-Host "Review the generated evidence first."

git status --short
