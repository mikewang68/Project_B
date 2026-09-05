$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $projectRoot '.run\mt-wms.pid'
$jarPath = Join-Path $projectRoot 'target\mt-wms.jar'

if (-not (Test-Path $pidFile)) {
    Write-Host '没有找到 MT-WMS 本地运行记录。'
    exit 0
}

$processId = Get-Content $pidFile
$processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue

if (-not $processInfo) {
    Remove-Item -LiteralPath $pidFile
    Write-Host 'MT-WMS 已经停止。'
    exit 0
}

if ($processInfo.CommandLine -notlike "*$jarPath*") {
    throw "PID $processId 不是当前 MT-WMS 进程，已拒绝停止。"
}

Stop-Process -Id $processId
Remove-Item -LiteralPath $pidFile
Write-Host 'MT-WMS 已停止。'
