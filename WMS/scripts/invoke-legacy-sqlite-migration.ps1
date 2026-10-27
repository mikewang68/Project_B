param(
    [ValidateSet('precheck','execute','verify')]
    [string]$Mode = 'precheck',
    [string]$BusinessDatabase,
    [string]$AuthDatabase,
    [string]$SourceCompany = 'default',
    [string]$TargetCompany = 'default'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
if (-not $BusinessDatabase) { $BusinessDatabase = Join-Path $workspaceRoot 'mt-wms-demo\.demo-data\wmsbase.sqlite3' }
if (-not $AuthDatabase) { $AuthDatabase = Join-Path $workspaceRoot 'mt-wms-demo\.demo-data\auth.sqlite3' }
$businessPath = [IO.Path]::GetFullPath($BusinessDatabase)
$authPath = [IO.Path]::GetFullPath($AuthDatabase)
foreach ($path in @($businessPath,$authPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "SQLite 数据库不存在：$path" }
}

$python = Join-Path $workspaceRoot '.venv-mt-wms\Scripts\python.exe'
$tool = Join-Path $projectRoot 'tools\legacy-migration\legacy_migrate.py'
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.env.local')) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}
[Environment]::SetEnvironmentVariable('PYTHONIOENCODING','utf-8','Process')

& $python $tool $Mode --source-type sqlite `
    --source-sqlite-business $businessPath --source-sqlite-auth $authPath `
    --source-company $SourceCompany --target-company $TargetCompany `
    --source-database ([IO.Path]::GetFileNameWithoutExtension($businessPath))
if ($LASTEXITCODE -ne 0) { throw "SQLite 旧库迁移 $Mode 未通过，退出码：$LASTEXITCODE" }
