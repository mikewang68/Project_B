param(
  [Parameter(Mandatory=$true)][string]$OutputDirectory,
  [Parameter(Mandatory=$true)][string]$SelfcheckHome
)
$ErrorActionPreference='Stop'
$auditRoot=Split-Path -Parent $PSScriptRoot
$repoRoot=Split-Path -Parent $auditRoot

function Read-PassingTests([string]$Directory,[int]$Expected){
  $cases=@()
  foreach($file in Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -File){
    [xml]$report=Get-Content -LiteralPath $file.FullName -Raw
    foreach($case in $report.SelectNodes('//testcase')){
      if($case.SelectNodes('failure|error|skipped').Count -ne 0){throw "Not all tests passed: $($file.Name)"}
      $cases += [ordered]@{class=$case.GetAttribute('classname');name=$case.GetAttribute('name')}
    }
  }
  if($cases.Count -ne $Expected){throw "Expected $Expected passing tests, found $($cases.Count) in $Directory"}
  return [ordered]@{tests=$cases.Count;failures=0;errors=0;skipped=0;cases=$cases}
}

$sdk=Read-PassingTests (Join-Path $auditRoot 'client/target/surefire-reports') 13
$service=Read-PassingTests (Join-Path $auditRoot 'service/target/surefire-reports') 28
$ehm=Read-PassingTests (Join-Path $repoRoot 'EHMS/source/backend/target/surefire-reports') 124
[xml]$nodeReport=Get-Content -LiteralPath (Join-Path $auditRoot '.local/ehm-node-tests.xml') -Raw
$nodeCases=@($nodeReport.SelectNodes('//testcase'))
if($nodeCases.Count -ne 16 -or $nodeReport.SelectNodes('//failure|//error|//skipped').Count -ne 0){throw 'EHM Node tests are missing or not all passing'}
$node=[ordered]@{tests=16;failures=0;errors=0;skipped=0;cases=@($nodeCases | ForEach-Object {[ordered]@{name=$_.GetAttribute('name')}})}

# Explicit allowlist: never export connection files, tokens, request bodies, paths or raw tool reports.
$official=@()
foreach($scenario in @('normal','disconnect','unavailable','timeout','action-required')){
  $run=Get-ChildItem -LiteralPath (Join-Path $SelfcheckHome '.local') -Directory -Filter "audit-$scenario-*" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if(-not $run){throw "Missing official simulator scenario: $scenario"}
  $summary=Get-Content -LiteralPath (Join-Path $run.FullName 'summary.json') -Raw | ConvertFrom-Json
  $caller=Get-Content -LiteralPath (Join-Path $run.FullName 'caller-state.json') -Raw | ConvertFrom-Json
  $requestCheck=Get-Content -LiteralPath (Join-Path $run.FullName 'check.json') -Raw | ConvertFrom-Json
  if(-not $summary.completed -or $summary.exitCode -ne 0 -or -not $caller.callerPassed -or $requestCheck.exitCode -ne 0 -or $summary.mode -ne 'local-simulation'){throw "Incomplete simulator evidence: $scenario"}
  $official += [ordered]@{
    scenario=$scenario;mode='LOCAL_SIMULATION_ONLY';toolVersion=$summary.buildVersion;contractVersion=$summary.contractVersion
    completed=$true;exitCode=0;callerPassed=$true;localState=$caller.chainState
    checks=@($summary.checks | ForEach-Object {[ordered]@{name=$_.name;passed=[bool]$_.passed}})
  }
}
$artifacts=@()
foreach($relative in @('AUDIT/client/target/audit-client-1.0.1.jar','AUDIT/service/target/audit-service-1.1.0.jar','EHMS/source/backend/target/ehm-demo-0.1.0.jar')){
  $file=Get-Item -LiteralPath (Join-Path $repoRoot $relative)
  $artifacts += [ordered]@{path=$relative;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$httpRun=Get-ChildItem -LiteralPath (Join-Path $auditRoot '.local') -Directory -Filter 'http-e2e-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$httpReport=Get-Content -LiteralPath (Join-Path $httpRun.FullName 'sanitized-report.json') -Raw | ConvertFrom-Json
if(-not $httpReport.passed -or $httpReport.chainEnabled -or $httpReport.scope -ne 'LOCAL_REAL_HTTP_SYNTHETIC_H2_ONLY'){throw 'Latest local HTTP test did not pass'}
foreach($artifact in $artifacts | Where-Object {$_.path -ne 'AUDIT/client/target/audit-client-1.0.1.jar'}){
  if($httpReport.artifacts.($artifact.path) -ne $artifact.sha256){throw 'Local HTTP test artifact hashes are stale; rerun after the final build'}
}
$evidence=[ordered]@{
  formatVersion=1;createdAt=(Get-Date).ToUniversalTime().ToString('o')
  scope='LOCAL_TESTS_AND_OFFICIAL_OFFLINE_SIMULATOR_ONLY'
  versions=[ordered]@{service='1.1.0';sdk='1.0.1';ehm='1.7.3-server'}
  source=[ordered]@{baselineCommit=(git -C $repoRoot rev-parse HEAD);workingTree='UNCOMMITTED_LOCAL_CHANGES';supplierReportedCommit='de59e99c6002895f89247774066e0c5aa557dd5e';supplierPacketFilesHashChecked=12}
  tests=[ordered]@{sdk=$sdk;service=$service;ehmBackend=$ehm;ehmNode=$node}
  officialSimulator=[ordered]@{includedInServiceTestCount=$true;scenarios=$official}
  localHttpIntegration=[ordered]@{scope=$httpReport.scope;checks=$httpReport.checks;passed=$httpReport.passed;realIam=$false;realDatabase=$false;realChain=$false}
  artifacts=$artifacts
  realChainEnabled=$false;liveDatabaseVerified=$false;liveFabricOrIpfsVerified=$false;serverDeployed=$false;githubPushed=$false
  limitations=@('H2 and mocks are not real openGauss acceptance','Five official scenarios, not every official scenario','No real source credentials or content archival approval','No raw tool reports, connection data or business payloads are exported')
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$target=Join-Path $OutputDirectory 'test-evidence.json'
$evidence | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $target -Encoding UTF8
Copy-Item -LiteralPath (Join-Path $httpRun.FullName 'sanitized-report.json') -Destination (Join-Path $OutputDirectory 'local-http-e2e.json')
Copy-Item -LiteralPath (Join-Path $repoRoot 'EHMS/source/backend/target/audit-route-coverage.json') -Destination (Join-Path $OutputDirectory 'audit-route-coverage.json')
Get-Item -LiteralPath $target | Select-Object Name,Length
