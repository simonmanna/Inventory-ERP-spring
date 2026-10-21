# Windows (PowerShell 5.1+) twin of scripts/upgrade.sh for single-site installs running Docker Desktop.
#   powershell -ExecutionPolicy Bypass -File scripts\upgrade.ps1 [-Pull]
# Pre-upgrade dump -> tag running images :rollback -> build/pull -> recreate api+web -> wait for readiness ->
# automatic image rollback when the new version does not become ready. The database is never rolled back here.
param([switch]$Pull)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$readyUrl = if ($env:READY_URL) { $env:READY_URL } else { 'http://127.0.0.1:3001/api/v1/health/ready' }
$timeout = if ($env:READY_TIMEOUT) { [int]$env:READY_TIMEOUT } else { 240 }
$backupDir = if ($env:UPGRADE_BACKUP_DIR) { $env:UPGRADE_BACKUP_DIR } else { '.\var\upgrade-backups' }

function Log($m) { Write-Host ("[upgrade {0}] {1}" -f (Get-Date).ToUniversalTime().ToString('HH:mm:ss'), $m) }

$state = docker compose ps db --format '{{.State}}'
if ($state -notmatch 'running') { throw "database container 'db' is not running" }

New-Item -ItemType Directory -Force $backupDir | Out-Null
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$dump = Join-Path $backupDir "pre-upgrade-$stamp.dump"
Log "Dumping the database to $dump"
# Binary-safe: run pg_dump to a file inside the container, then copy it out.
docker compose exec -T db sh -c 'pg_dump --format=custom --no-owner -U "$POSTGRES_USER" -f /tmp/pre-upgrade.dump "$POSTGRES_DB"'
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed - aborting before touching anything' }
$dbId = docker compose ps -q db
docker cp "${dbId}:/tmp/pre-upgrade.dump" $dump
if ((Get-Item $dump).Length -eq 0) { throw 'pre-upgrade dump is empty' }
(Get-FileHash $dump -Algorithm SHA256).Hash.ToLower() + " *" + (Split-Path $dump -Leaf) | Set-Content -Encoding ascii "$dump.sha256"
Set-Content -Encoding ascii (Join-Path $backupDir 'last-upgrade-dump') $dump

foreach ($svc in 'api', 'web') {
  $img = docker compose images -q $svc | Select-Object -First 1
  if ($img) { docker tag $img "inventory-erp-${svc}:rollback"; Log "Tagged $svc $img as rollback" }
}

if ($Pull) { docker compose pull api web } else { docker compose build api web }
if ($LASTEXITCODE -ne 0) { throw 'image build/pull failed (running version untouched)' }
docker compose up -d --no-deps api web

$deadline = (Get-Date).AddSeconds($timeout)
while ($true) {
  try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 $readyUrl; if ($r.StatusCode -eq 200) { break } } catch { }
  if ((Get-Date) -gt $deadline) {
    Log 'New version did not become ready - restoring the previous images'
    docker compose logs --tail=200 api
    foreach ($svc in 'api', 'web') {
      $target = docker compose config --images | Where-Object { $_ -match "-$svc$" } | Select-Object -First 1
      if ($target) { docker tag "inventory-erp-${svc}:rollback" $target }
    }
    docker compose up -d --no-deps --no-build api web
    throw "upgrade failed; previous version restored (database untouched; dump at $dump)"
  }
  Start-Sleep -Seconds 5
}
Log "Upgrade complete. Pre-upgrade dump: $dump"
