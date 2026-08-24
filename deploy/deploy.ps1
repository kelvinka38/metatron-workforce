$ErrorActionPreference = "Stop"

$BaseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = Split-Path -Parent $BaseDir
Set-Location $RootDir

$EnvFile = if ($env:METATRON_ENV_FILE) { $env:METATRON_ENV_FILE } else { Join-Path $RootDir ".env" }

if (-not (Test-Path $EnvFile)) {
    throw "Missing deployment env file: $EnvFile"
}

Write-Host "=== DOCKER PREFLIGHT ===" -ForegroundColor Cyan
docker version | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "Docker Linux engine is not reachable. Start Docker Desktop before deployment."
}

Write-Host "=== ENV PREFLIGHT ===" -ForegroundColor Cyan
$required = @(
    "TELEGRAM_BOT_TOKEN",
    "TELEGRAM_WEBHOOK_SECRET",
    "TELEGRAM_ALLOWED_USER_ID",
    "METATRON_ORGANIZATION_ID",
    "METATRON_GATEWAY_AUDIT_URL"
)

$envMap = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $envMap[$matches[1].Trim()] = $matches[2].Trim()
    }
}

foreach ($name in $required) {
    if (-not $envMap.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($envMap[$name])) {
        throw "Required deployment variable is missing: $name"
    }
}

$placeholderPatterns = @(
    "your-real-",
    "sk-your-real-",
    "replace-me",
    "changeme",
    "example"
)

foreach ($name in @("TELEGRAM_BOT_TOKEN", "TELEGRAM_WEBHOOK_SECRET", "METATRON_GATEWAY_AUDIT_URL", "OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY")) {
    if ($envMap.ContainsKey($name)) {
        foreach ($pattern in $placeholderPatterns) {
            if ($envMap[$name] -match [regex]::Escape($pattern)) {
                throw "Placeholder value detected for $name"
            }
        }
    }
}

$env:METATRON_COMMIT_SHA = (git rev-parse HEAD)
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve Git HEAD" }
$env:METATRON_VERSION = if ($env:METATRON_VERSION) { $env:METATRON_VERSION } else { "0.1.0" }
$env:METATRON_ENVIRONMENT = if ($env:METATRON_ENVIRONMENT) { $env:METATRON_ENVIRONMENT } else { "production" }

Write-Host "=== BUILD ===" -ForegroundColor Cyan
& .\gradlew.bat clean test bootJar --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle build/test failed" }

Write-Host "=== DEPLOY ===" -ForegroundColor Cyan
$composeArgs = @("compose", "--env-file", $EnvFile, "-f", ".\deploy\docker-compose.yml", "up", "-d", "--build")
& docker @composeArgs
if ($LASTEXITCODE -ne 0) { throw "Docker Compose deployment failed" }

Write-Host "=== HEALTH ===" -ForegroundColor Cyan
$healthy = $false
for ($i = 1; $i -le 45; $i++) {
    try {
        $health = Invoke-RestMethod "http://127.0.0.1:8080/actuator/health"
        if ($health.status -eq "UP") {
            $healthy = $true
            break
        }
    } catch { }
    Start-Sleep -Seconds 2
}

if (-not $healthy) {
    & docker compose --env-file $EnvFile -f ".\deploy\docker-compose.yml" logs --tail 200 workforce
    throw "Workforce health check failed"
}

Write-Host "=== PROVENANCE ===" -ForegroundColor Cyan
Write-Host "DEPLOYED_COMMIT_SHA=$env:METATRON_COMMIT_SHA"
Write-Host "METATRON_VERSION=$env:METATRON_VERSION"
Write-Host "METATRON_ENVIRONMENT=$env:METATRON_ENVIRONMENT"

Write-Host "WORKFORCE DEPLOYMENT GATE: PASS" -ForegroundColor Green
