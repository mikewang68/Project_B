param(
    [switch]$Build,
    [switch]$NoBrowser,
    [switch]$SkipDatabase
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$jarPath = Join-Path $projectRoot 'target\mt-wms.jar'
$runDirectory = Join-Path $projectRoot '.run'
$pidFile = Join-Path $runDirectory 'mt-wms.pid'
$stdoutLog = Join-Path $runDirectory 'mt-wms.out.log'
$stderrLog = Join-Path $runDirectory 'mt-wms.err.log'
$siteUrl = 'http://127.0.0.1:8080/'
$healthUrl = 'http://127.0.0.1:8080/health/live'
$environmentFile = Join-Path $projectRoot '.env.local'

$jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $jdk) {
    throw '未找到 Microsoft OpenJDK 17，请先安装 Java 17。'
}

if (Test-Path $environmentFile) {
    foreach ($line in Get-Content $environmentFile) {
        if ($line -match '^\s*([^#][^=]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
        }
    }
}

if (-not $SkipDatabase) {
    & (Join-Path $PSScriptRoot 'start-opengauss.ps1')
}

if ($Build -or -not (Test-Path $jarPath)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'MT-WMS 构建失败。'
    }
}

New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

if (Test-Path $pidFile) {
    $existingPid = Get-Content $pidFile -ErrorAction SilentlyContinue
    $existingProcess = if ($existingPid) { Get-Process -Id $existingPid -ErrorAction SilentlyContinue } else { $null }
    if ($existingProcess) {
        Write-Host "MT-WMS 已在运行，PID：$existingPid"
        if (-not $NoBrowser) { Start-Process $siteUrl }
        exit 0
    }
}

$javaExecutable = Join-Path $jdk.FullName 'bin\java.exe'
$processOptions = @{
    FilePath = $javaExecutable
    ArgumentList = @('-jar', "`"$jarPath`"", '--spring.profiles.active=dev')
    WorkingDirectory = $projectRoot
    WindowStyle = 'Hidden'
    RedirectStandardOutput = $stdoutLog
    RedirectStandardError = $stderrLog
    PassThru = $true
}
$process = Start-Process @processOptions

$process.Id | Set-Content -Path $pidFile -Encoding ascii

$ready = $false
foreach ($attempt in 1..20) {
    Start-Sleep -Seconds 1
    try {
        $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        if ($process.HasExited) { break }
    }
}

if (-not $ready) {
    $recentError = if (Test-Path $stderrLog) { (Get-Content $stderrLog -Tail 20) -join [Environment]::NewLine } else { '' }
    throw "MT-WMS 未能在预期时间内启动。日志：$stderrLog`n$recentError"
}

Write-Host "MT-WMS 已启动，PID：$($process.Id)"
Write-Host "访问地址：$siteUrl"
try {
    $databaseHealth = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/health/ready' -UseBasicParsing -TimeoutSec 5
    if ($databaseHealth.StatusCode -eq 200) {
        Write-Host '数据库状态：UP'
    }
} catch {
    Write-Warning '数据库状态：DOWN；页面可访问，但依赖数据库的功能不可用。'
}

if (-not $NoBrowser) {
    Start-Process $siteUrl
}
