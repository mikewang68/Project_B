param([string]$JavaHome='', [string]$Maven='', [switch]$SkipBuild, [switch]$Stop)
$ErrorActionPreference='Stop'
$ehmRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runRoot=Join-Path $ehmRoot '.local/simulation'
$statePath=Join-Path $runRoot 'process.json'
$utf8=New-Object System.Text.UTF8Encoding($false)
if(Test-Path -LiteralPath $statePath){
    $saved=Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $process=Get-CimInstance Win32_Process -Filter ('ProcessId = '+[int]$saved.pid)
    if($process -and $process.CommandLine.Contains([string]$saved.jar)){
        if($Stop){Stop-Process -Id $saved.pid; Write-Host 'Local simulation stopped. Database and logs retained.'; exit 0}
        Write-Host 'Simulation already running: http://127.0.0.1:18294/?page=sample-workbench'; exit 0
    }
}
if($Stop){Write-Host 'No matching simulation process. No other process was stopped.'; exit 0}
if(Get-NetTCPConnection -LocalPort 18294 -State Listen -ErrorAction SilentlyContinue){throw 'Port 18294 is occupied. No process was stopped.'}
if(!$JavaHome){$JavaHome=$env:JAVA_HOME}
if(!$JavaHome){$javaCmd=Get-Command java.exe -ErrorAction SilentlyContinue;if($javaCmd){$JavaHome=Split-Path (Split-Path $javaCmd.Source)}}
if(!$JavaHome){throw 'Java 17 required. Pass -JavaHome <JDK17 directory>.'}
$java=Join-Path $JavaHome 'bin/java.exe'
if(!(Test-Path -LiteralPath $java)){throw 'Java executable not found.'}
$releasePath=Join-Path $JavaHome 'release'
if(!(Test-Path -LiteralPath $releasePath)){throw 'Cannot verify Java runtime. Pass -JavaHome pointing to a JDK 17 or newer installation.'}
$versionText=[IO.File]::ReadAllText($releasePath)
if($versionText -notmatch '(?m)^JAVA_VERSION="(?:1\.)?(\d+)'){
    throw 'Cannot read JAVA_VERSION from JDK release file. A JDK 17 or newer is required.'
}
if([int]$Matches[1] -lt 17){throw ('Java '+$Matches[1]+' is too old. Pass -JavaHome pointing to JDK 17 or newer; global Java settings were not changed.')}
if(!$Maven){$mvnCmd=Get-Command mvn.cmd -ErrorAction SilentlyContinue;if($mvnCmd){$Maven=$mvnCmd.Source}}
if(!$SkipBuild){
    if(!$Maven){throw 'Maven required. Pass -Maven <absolute mvn.cmd path> or build first and use -SkipBuild.'}
    $env:JAVA_HOME=$JavaHome
    Push-Location (Join-Path $ehmRoot 'source/backend')
    try{& $Maven -q -Psimulation-local -DskipTests package;if($LASTEXITCODE -ne 0){throw 'Build failed; no service started.'}}finally{Pop-Location}
}
$buildJar=Join-Path $ehmRoot 'source/backend/target/ehm-demo-0.1.0.jar'
if(!(Test-Path -LiteralPath $buildJar)){throw 'Build JAR missing.'}
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
# Private credentials and database are outside the tracked source tree and readable only locally.
$acl=New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true,$false)
$owner=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$rule=New-Object System.Security.AccessControl.FileSystemAccessRule($owner,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
$acl.AddAccessRule($rule);(Get-Item -LiteralPath $runRoot).SetAccessControl($acl)
function New-LocalSecret {
    $bytes=New-Object byte[] 24;$rng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes);return [Convert]::ToBase64String($bytes)}finally{$rng.Dispose()}
}
$accountsPath=Join-Path $runRoot 'accounts.private.json'
if(!(Test-Path -LiteralPath $accountsPath)){
    $accounts=@(@{username='simadmin';password=(New-LocalSecret);displayName='Local simulation admin';role='SUPER_ADMIN'},@{username='simviewer';password=(New-LocalSecret);displayName='Local simulation viewer';role='USER'})
    [IO.File]::WriteAllText($accountsPath,($accounts | ConvertTo-Json -Depth 4),$utf8)
}
$dbSecret=Join-Path $runRoot 'database.password'
if(!(Test-Path -LiteralPath $dbSecret)){[IO.File]::WriteAllText($dbSecret,(New-LocalSecret),$utf8)}
$jar=Join-Path $runRoot 'ehm-simulation.jar';Copy-Item -LiteralPath $buildJar -Destination $jar -Force
$envBackup=@{}
Get-ChildItem Env: | Where-Object Name -like 'EHM_*' | ForEach-Object {$envBackup[$_.Name]=$_.Value;Remove-Item ('Env:'+$_.Name)}
try{
    $env:EHM_SIM_DB_PASSWORD=[IO.File]::ReadAllText($dbSecret)
    $env:EHM_SIM_BOOTSTRAP_FILE=$accountsPath
    $webPath=(Join-Path $ehmRoot 'web').Replace('\','/')+'/'
    $env:EHM_SIM_WEB_LOCATION=([Uri]$webPath).AbsoluteUri
    $arguments=@('-Xms128m','-Xmx768m','-Dfile.encoding=UTF-8','-jar',('"'+$jar+'"'),'--spring.profiles.active=server,simulation-local')
    $started=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runRoot 'service.log') -RedirectStandardError (Join-Path $runRoot 'service-error.log')
    [IO.File]::WriteAllText($statePath,(@{pid=$started.Id;jar=$jar;startedAt=(Get-Date).ToString('o')} | ConvertTo-Json),$utf8)
}finally{
    Get-ChildItem Env: | Where-Object Name -like 'EHM_*' | ForEach-Object {Remove-Item ('Env:'+$_.Name)}
    foreach($entry in $envBackup.GetEnumerator()){[Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')}
}
$ready=$false
for($i=0;$i -lt 45;$i++){
    Start-Sleep -Seconds 1
    if($started.HasExited){throw 'Service exited. Inspect .local/simulation/service.log and service-error.log.'}
    try{$response=Invoke-WebRequest 'http://127.0.0.1:18294/api/ehm/v1/auth/me' -UseBasicParsing -TimeoutSec 1;if($response.StatusCode -eq 200){$ready=$true;break}}
    catch{if($_.Exception.Response.StatusCode.value__ -eq 401){$ready=$true;break}}
}
if(!$ready){throw 'Startup timeout. Process retained for diagnosis; inspect local logs.'}
Write-Host 'Ready: http://127.0.0.1:18294/?page=sample-workbench'
Write-Host ('Private local accounts: '+$accountsPath)
Write-Host 'No server deployment, external AI calls, public audit delivery or blockchain writes.'
