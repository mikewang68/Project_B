param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [Parameter(Mandatory = $true)]
    [string]$ConfirmDatabaseName
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedBackup = [IO.Path]::GetFullPath($BackupFile)
if (-not (Test-Path -LiteralPath $resolvedBackup -PathType Leaf)) { throw "备份文件不存在：$resolvedBackup" }

$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.env.local')) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
$database = $settings['GS_DB']; $password = $settings['GS_PASSWORD']
if ($ConfirmDatabaseName -cne $database) { throw "确认数据库名不匹配；必须明确传入 -ConfirmDatabaseName $database。" }
if (-not $password) { throw '.env.local 缺少 GS_PASSWORD。' }

$hashFile = "$resolvedBackup.sha256"
if (Test-Path -LiteralPath $hashFile -PathType Leaf) {
    $expected = (Get-Content -LiteralPath $hashFile -Raw).Trim()
    $actual = (Get-FileHash -LiteralPath $resolvedBackup -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $expected) { throw '备份 SHA256 校验失败，拒绝恢复。' }
}

& (Join-Path $PSScriptRoot 'stop-local.ps1')
$containerPath = "/tmp/restore-$([IO.Path]::GetFileName($resolvedBackup))"
docker cp $resolvedBackup "mt-wms-opengauss:$containerPath"
if ($LASTEXITCODE -ne 0) { throw '复制备份到容器失败。' }
docker exec -e 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/scws/lib' mt-wms-opengauss `
    /usr/local/opengauss/bin/gsql -d $database -U omm -W $password -v ON_ERROR_STOP=1 -f $containerPath
if ($LASTEXITCODE -ne 0) { throw 'openGauss 恢复失败；应用保持停止状态，请检查输出。' }
docker exec mt-wms-opengauss /bin/rm -f $containerPath | Out-Null
& (Join-Path $PSScriptRoot 'migrate-opengauss.ps1')
& (Join-Path $PSScriptRoot 'start-local.ps1') -NoBrowser -SkipDatabase
Write-Host '恢复完成并已重新启动 MT-WMS。'
