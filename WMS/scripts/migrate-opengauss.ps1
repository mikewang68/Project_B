$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectRoot '.env.local'
$migrationDirectory = Join-Path $projectRoot 'database\migrations'
$containerName = 'mt-wms-opengauss'
$gsql = '/usr/local/opengauss/bin/gsql'
$libraryPath = 'LD_LIBRARY_PATH=/usr/local/opengauss/lib:/scws/lib'
$settings = @{}

foreach ($line in Get-Content $environmentFile) {
    if ($line -match '^\s*([^#][^=]*)=(.*)$') {
        $settings[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$database = $settings['GS_DB']
$superPassword = $settings['GS_PASSWORD']
if (-not $database -or -not $superPassword) {
    throw '.env.local 缺少 GS_DB 或 GS_PASSWORD。'
}

function Invoke-Gsql {
    param([string[]]$Arguments)

    $baseArguments = @(
        'exec', '-e', $libraryPath, $containerName, $gsql,
        '-d', $database, '-U', 'omm', '-W', $superPassword,
        '-v', 'ON_ERROR_STOP=1'
    )
    $output = & docker @baseArguments @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "数据库命令执行失败：$($Arguments -join ' ')"
    }
    return $output
}

$migrationFiles = Get-ChildItem $migrationDirectory -File -Filter 'V*.sql' | Sort-Object Name
foreach ($file in $migrationFiles) {
    if ($file.Name -notmatch '^V(\d+)__(.+)\.sql$') {
        throw "迁移脚本命名不符合 V数字__说明.sql：$($file.Name)"
    }

    $version = $matches[1]
    $description = $matches[2] -replace '_', ' '
    $checksum = (Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $query = "SELECT checksum FROM schema_version WHERE version='$version' AND success=true;"
    $installedValue = Invoke-Gsql @('-t', '-A', '-c', $query) | Select-Object -First 1
    $installedChecksum = if ($null -eq $installedValue) { '' } else { ([string]$installedValue).Trim() }

    if ($installedChecksum) {
        if ($installedChecksum -ne $checksum) {
            throw "迁移脚本 V$version 的校验值已变化，拒绝继续。"
        }
        Write-Host "V$version 已执行，跳过。"
        continue
    }

    Invoke-Gsql @('-f', "/docker-entrypoint-initdb.d/$($file.Name)") | Out-Host
    $safeDescription = $description.Replace("'", "''")
    $record = "INSERT INTO schema_version(version, description, checksum, success) VALUES ('$version', '$safeDescription', '$checksum', true);"
    Invoke-Gsql @('-c', $record) | Out-Host
    Write-Host "V$version 执行成功。"
}
