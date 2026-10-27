$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'
$jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $jdk) {
    throw '未找到 Microsoft OpenJDK 17，请先安装 Java 17。'
}

$env:JAVA_HOME = $jdk.FullName
$env:Path = "$(Join-Path $jdk.FullName 'bin');$env:Path"

$javaVersion = (& java -version 2>&1 | Select-Object -First 1) -join ''
$mavenVersion = (& $mavenWrapper -version | Select-Object -First 1) -join ''

if ($javaVersion -notmatch '"17') {
    throw "需要 Java 17，当前为：$javaVersion"
}
if ($mavenVersion -notmatch 'Apache Maven 3\.9\.') {
    throw "需要 Maven 3.9.x，当前为：$mavenVersion"
}

Push-Location $projectRoot
try {
    & $mavenWrapper clean package
} finally {
    Pop-Location
}
