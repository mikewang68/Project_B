$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot 'docker-compose.yml'
$imageName = 'opengauss:6.0.5'
$containerName = 'mt-wms-opengauss'

docker info *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Desktop 尚未启动。'
}

docker image inspect $imageName *> $null
if ($LASTEXITCODE -ne 0) {
    throw "未找到 $imageName，请先导入官方 openGauss 6.0.5 镜像。"
}

Push-Location $projectRoot
try {
    docker compose -f $composeFile up -d
    if ($LASTEXITCODE -ne 0) { throw 'openGauss 容器启动失败。' }

    $healthy = $false
    foreach ($attempt in 1..60) {
        $status = docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerName 2>$null
        if ($status -eq 'healthy') {
            $healthy = $true
            break
        }
        if ($status -eq 'exited' -or $status -eq 'dead') { break }
        Start-Sleep -Seconds 3
    }

    if (-not $healthy) {
        docker compose -f $composeFile logs --tail 80 opengauss
        throw 'openGauss 未能通过健康检查。'
    }

    & (Join-Path $PSScriptRoot 'migrate-opengauss.ps1')
    Write-Host 'openGauss 6.0.5 已启动并通过健康检查。'
    Write-Host '数据库：mt_wms；端口：127.0.0.1:5432'
} finally {
    Pop-Location
}
