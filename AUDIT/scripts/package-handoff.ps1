param([string]$OutputDirectory='', [string]$HandoffDocumentsDirectory='')
$ErrorActionPreference='Stop'
$auditRoot=Split-Path -Parent $PSScriptRoot
$repoRoot=Split-Path -Parent $auditRoot
if(-not $OutputDirectory){$OutputDirectory=Join-Path (Split-Path -Parent $repoRoot) 'outputs\audit-handoff'}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$stage=Join-Path $OutputDirectory "EHM-Audit-handoff-v1.2-$stamp"
New-Item -ItemType Directory -Path $stage | Out-Null
# Only Git's public EHMS inventory plus the dedicated common-service source. No runtime/private config.
[Console]::OutputEncoding=New-Object System.Text.UTF8Encoding($false)
$ehmFiles=git -C $repoRoot -c core.quotepath=false ls-files --cached --others --exclude-standard -- EHMS
foreach($relative in $ehmFiles){
  if($relative -match '(^|/)(target|runtime|\.local|node_modules|\.git)/' -or $relative -match '\.(token|key|pem|log|zip)$'){continue}
  $source=Join-Path $repoRoot $relative
  if(-not(Test-Path -LiteralPath $source -PathType Leaf)){continue}
  $target=Join-Path $stage $relative
  New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
  Copy-Item -LiteralPath $source -Destination $target
}
Get-ChildItem -LiteralPath $auditRoot -File -Recurse | ForEach-Object {
  $relative=$_.FullName.Substring($auditRoot.Length+1).Replace('\','/')
  if($relative -match '(^|/)(target|runtime|\.local|node_modules|__pycache__)/' -or $relative -match '\.(token|key|pem|log|pyc)$'){return}
  $target=Join-Path $stage ('AUDIT/'+$relative)
  New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
  Copy-Item -LiteralPath $_.FullName -Destination $target
}
$artifacts=@(
  @{Source='AUDIT/service/target/audit-service-1.1.0.jar';Target='AUDIT/service/target/audit-service-1.1.0.jar'},
  @{Source='AUDIT/client/target/audit-client-1.0.1.jar';Target='AUDIT/client/target/audit-client-1.0.1.jar'},
  @{Source='EHMS/source/backend/target/ehm-demo-0.1.0.jar';Target='EHMS/app/ehm-service.jar'}
)
foreach($artifact in $artifacts){
  $target=Join-Path $stage $artifact.Target
  New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $repoRoot $artifact.Source) -Destination $target
}
# Detect stale Boot dependency caches even when a previous build exited successfully.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expectedSdk=(Get-FileHash -LiteralPath (Join-Path $repoRoot 'AUDIT/client/target/audit-client-1.0.1.jar') -Algorithm SHA256).Hash
foreach($relative in @('AUDIT/service/target/audit-service-1.1.0.jar','EHMS/app/ehm-service.jar')){
  $jar=[IO.Compression.ZipFile]::OpenRead((Join-Path $stage $relative))
  try{
    $entry=$jar.GetEntry('BOOT-INF/lib/audit-client-1.0.1.jar')
    if(-not $entry){throw "New SDK missing in $relative"}
    $stream=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create()
    try{$actual=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','')}finally{$stream.Dispose();$sha.Dispose()}
    if($actual -ne $expectedSdk){throw "Stale SDK embedded in $relative; rebuild before exporting"}
  }finally{$jar.Dispose()}
}
$forbidden=Get-ChildItem -LiteralPath $stage -File -Recurse | Where-Object { $_.FullName -match '(\\runtime\\|\\\.local\\|\.token$|\.key$|\\clients\.json$)' }
if($forbidden){throw 'Private/runtime files detected; package was not exported'}
if($HandoffDocumentsDirectory){
  $documents=Join-Path $stage 'HANDOFF'
  New-Item -ItemType Directory -Path $documents | Out-Null
  foreach($name in @('test-evidence.json','local-http-e2e.json','audit-route-coverage.json','交付说明-v1.7.3.md')){
    $source=Join-Path $HandoffDocumentsDirectory $name
    if(-not(Test-Path -LiteralPath $source -PathType Leaf)){throw "Missing handoff document: $name"}
    Copy-Item -LiteralPath $source -Destination (Join-Path $documents $name)
  }
  $evidence=Get-Content -LiteralPath (Join-Path $documents 'test-evidence.json') -Raw | ConvertFrom-Json
  foreach($record in $evidence.artifacts){
    $current=(Get-FileHash -LiteralPath (Join-Path $repoRoot $record.path) -Algorithm SHA256).Hash
    if($current -ne $record.sha256){throw 'Artifacts changed after evidence export; regenerate test-evidence.json'}
  }
}
$inventory=@(Get-ChildItem -LiteralPath $stage -File -Recurse | Sort-Object FullName | ForEach-Object {
  [ordered]@{path=$_.FullName.Substring($stage.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest=[ordered]@{formatVersion=1;createdAt=(Get-Date).ToUniversalTime().ToString('o');realChainEnabled=$false;files=$inventory}
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $stage 'FILE-SHA256.json') -Encoding UTF8
$archive="$stage.zip"
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Path,Hash
