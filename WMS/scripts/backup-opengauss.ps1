param([string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'backups' }
$resolvedProject = [IO.Path]::GetFullPath($projectRoot)
$resolvedOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null

$settings = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.env.local')) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
$database = $settings['GS_DB']; $password = $settings['GS_PASSWORD']
if (-not $database -or -not $password) { throw '.env.local 缺少 GS_DB 或 GS_PASSWORD。' }

$name = "${database}-$(Get-Date -Format 'yyyyMMdd-HHmmss').sql"
$hostPath = Join-Path $resolvedOutput $name
$containerPath = "/tmp/$name"
docker exec -e 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/scws/lib' mt-wms-opengauss `
    /usr/local/opengauss/bin/gs_dump -U omm -W $password -f $containerPath --clean --no-owner $database
if ($LASTEXITCODE -ne 0) { throw 'openGauss 备份失败。' }
docker cp "mt-wms-opengauss:$containerPath" $hostPath
if ($LASTEXITCODE -ne 0) { throw '从容器复制备份失败。' }
docker exec mt-wms-opengauss /bin/rm -f $containerPath | Out-Null

$hash = (Get-FileHash -LiteralPath $hostPath -Algorithm SHA256).Hash.ToLowerInvariant()
$hash | Set-Content -LiteralPath "$hostPath.sha256" -Encoding ascii
Write-Host "备份完成：$hostPath"
Write-Host "SHA256：$hash"
