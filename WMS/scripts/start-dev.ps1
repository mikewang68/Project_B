$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$frontendPath = Join-Path $projectRoot 'frontend'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $jdk) {
    throw '未找到 Microsoft OpenJDK 17，请先安装 Java 17。'
}

$env:JAVA_HOME = $jdk.FullName
$env:Path = "$(Join-Path $jdk.FullName 'bin');$env:Path"

Write-Host '启动前端开发服务器：http://127.0.0.1:5173/'
Start-Process -FilePath 'pnpm' -ArgumentList 'dev' -WorkingDirectory $frontendPath -WindowStyle Hidden

Write-Host '启动 Spring Boot：http://127.0.0.1:8080'
Push-Location $projectRoot
try {
    & $mavenWrapper spring-boot:run '-Dspring-boot.run.profiles=dev'
} finally {
    Pop-Location
}
