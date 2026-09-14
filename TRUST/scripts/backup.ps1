param([switch]$DryRun)
. "$PSScriptRoot/common.ps1"
$deployment=Get-TrustDeployment
if($DryRun){Write-Host "Backup configuration valid; no services or files changed.";return}
$projectRoot=Split-Path $PSScriptRoot -Parent
$remoteRoot=$deployment.remoteRoot.TrimEnd('/')
$appNode=$deployment.nodes.application.sshAlias
$localBackup=Join-Path $projectRoot '.local\backup-transfer'
New-Item -ItemType Directory -Force $localBackup | Out-Null
$stamp=(Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $appNode "curl -fsS http://127.0.0.1:28182/actuator/health >/dev/null"
$wasRunning=$LASTEXITCODE -eq 0
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $appNode "bash $remoteRoot/deploy/application.sh stop"
if($LASTEXITCODE -ne 0){throw 'Application could not be stopped; no coordinated backup taken'}
try {
ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $appNode "mkdir -p $remoteRoot/runtime/staging $remoteRoot/runtime/backups; tar -czf $remoteRoot/runtime/backups/staging-$stamp.tar.gz -C $remoteRoot/runtime staging"
if($LASTEXITCODE -ne 0){throw 'Reliable staging backup failed'}
$manifest=@("Development coordinated backup: $stamp", "staging-$stamp.tar.gz", 'Fabric ledger is retained separately on the Fabric node; this is not a complete consortium disaster recovery snapshot.')
foreach($item in @(@($deployment.nodes.ipfs.sshAlias,'ipfs','tar.gz'),@($deployment.nodes.database.sshAlias,'database','dump'))){
    $nodeName=$item[0];$component=$item[1];$extension=$item[2]
    ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $nodeName "bash $remoteRoot/deploy/$component.sh backup"
    if($LASTEXITCODE -ne 0){throw "Backup failed: $nodeName"}
    $backupPath=(ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $nodeName "ls -1t $remoteRoot/runtime/backups/*.$extension | head -1").Trim()
    if($LASTEXITCODE -ne 0 -or -not $backupPath.StartsWith("$remoteRoot/runtime/backups/")){throw 'Invalid backup path'}
    scp -o BatchMode=yes -o StrictHostKeyChecking=yes "${nodeName}:$backupPath" $localBackup
    if($LASTEXITCODE -ne 0){throw 'Backup transfer failed'}
    $name=Split-Path $backupPath -Leaf
    scp -o BatchMode=yes -o StrictHostKeyChecking=yes (Join-Path $localBackup $name) "${appNode}:$remoteRoot/runtime/backups/$name"
    if($LASTEXITCODE -ne 0){throw 'Backup copy to application node failed'}
    $localHash=(Get-FileHash -Algorithm SHA256 (Join-Path $localBackup $name)).Hash.ToLowerInvariant()
    $remoteHash=(ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $appNode "sha256sum $remoteRoot/runtime/backups/$name").Split(' ')[0]
    if($localHash -ne $remoteHash){throw 'Backup checksum mismatch'}
    $manifest += "$name SHA256=$localHash"
    Remove-Item -LiteralPath (Join-Path $localBackup $name)
    Write-Host "$nodeName backup copied and verified on application node: $name"
}
$manifestPath=Join-Path $localBackup "backup-$stamp.txt"
$manifest | Set-Content -LiteralPath $manifestPath -Encoding utf8
scp -o BatchMode=yes -o StrictHostKeyChecking=yes $manifestPath "${appNode}:$remoteRoot/runtime/backups/backup-$stamp.txt"
if($LASTEXITCODE -ne 0){throw 'Backup manifest transfer failed'}
Remove-Item -LiteralPath $manifestPath
} finally {
    if($wasRunning){
        ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $appNode "bash $remoteRoot/deploy/application.sh start"
        if($LASTEXITCODE -ne 0){Write-Warning 'Backup finished or failed, but application restart failed; inspect project logs'}
    }
}
