param(
    [ValidateSet('precheck','execute','verify')]
    [string]$Mode = 'precheck',
    [string]$SourceHost = '127.0.0.1',
    [int]$SourcePort = 3306,
    [string]$SourceDatabase = 'wmsbase',
    [string]$SourceUser = 'wms',
    [Parameter(Mandatory = $true)]
    [string]$SourcePassword,
    [string]$SourceCompany = 'default',
    [string]$TargetCompany = 'default'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectRoot '.env.local'
$python = Join-Path (Split-Path -Parent $projectRoot) '.venv-mt-wms\Scripts\python.exe'
$tool = Join-Path $projectRoot 'tools\legacy-migration\legacy_migrate.py'

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "项目 Python 环境不存在：$python"
}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

& $python $tool $Mode `
    --source-host $SourceHost --source-port $SourcePort --source-database $SourceDatabase `
    --source-user $SourceUser --source-password $SourcePassword --source-company $SourceCompany `
    --target-company $TargetCompany
if ($LASTEXITCODE -ne 0) {
    throw "旧库迁移 $Mode 未通过，退出码：$LASTEXITCODE"
}
