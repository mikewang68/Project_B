param([string[]]$Roles = @('ipfs','database','fabric','application'), [switch]$DryRun)
. "$PSScriptRoot/common.ps1"
$deployment = Get-TrustDeployment
$archivePath = Join-Path $script:TrustRoot '.local/source.tar.gz'
foreach ($role in $Roles) {
    if ($role -notin @('ipfs','database','fabric','application','gateway')) { throw "Unknown role: $role" }
    if ($role -eq 'gateway' -and -not $deployment.httpAccess.gateway) { throw 'HTTP gateway configuration is missing' }
}
& $script:TrustPython (Join-Path $script:TrustRoot 'scripts/package-source.py') --output $archivePath
if ($LASTEXITCODE -ne 0) { throw 'Source archive failed' }
if ($DryRun) {
    Write-Host 'Module archive prepared; configuration valid. No remote files or processes changed.'
    return
}
$remoteRoot = $deployment.remoteRoot.TrimEnd('/')
$configTransfer = Join-Path $script:TrustRoot '.local/deployment-transfer.json'
$deployment | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $configTransfer -Encoding utf8
try {
    foreach ($role in $Roles) {
        $nodeName = if ($role -eq 'gateway') { $deployment.httpAccess.gateway.sshAlias } else { $deployment.nodes.$role.sshAlias }
        ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $nodeName "umask 077; mkdir -p $remoteRoot/runtime/secrets"
        if ($LASTEXITCODE -ne 0) { throw "Remote directory failed: $role" }
        scp -o BatchMode=yes -o StrictHostKeyChecking=yes $archivePath "${nodeName}:$remoteRoot/source.tar.gz"
        if ($LASTEXITCODE -ne 0) { throw "Source copy failed: $role" }
        scp -o BatchMode=yes -o StrictHostKeyChecking=yes $configTransfer "${nodeName}:$remoteRoot/runtime/secrets/deployment.json.tmp"
        if ($LASTEXITCODE -ne 0) { throw "Private configuration copy failed: $role" }
        ssh -o BatchMode=yes -o StrictHostKeyChecking=yes $nodeName "chmod 600 $remoteRoot/runtime/secrets/deployment.json.tmp && mv $remoteRoot/runtime/secrets/deployment.json.tmp $remoteRoot/runtime/secrets/deployment.json && tar -xzf $remoteRoot/source.tar.gz -C $remoteRoot && rm $remoteRoot/source.tar.gz && python3 $remoteRoot/deploy/retire-source-paths.py"
        if ($LASTEXITCODE -ne 0) { throw "Source extraction or layout migration failed: $role" }
    }
} finally {
    Remove-Item -LiteralPath $configTransfer -ErrorAction SilentlyContinue
}
