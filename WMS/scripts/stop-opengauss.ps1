$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    docker compose stop opengauss
    if ($LASTEXITCODE -ne 0) { throw '停止 openGauss 失败。' }
    Write-Host 'openGauss 已停止，数据库卷仍然保留。'
} finally {
    Pop-Location
}
