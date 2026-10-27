param([string]$BaseUrl=$env:WMS_BASE_URL,[string]$CredentialFile,[string]$CaCert=$env:WMS_CA_CERT,[string]$ResolveHost,[switch]$SkipChat)
. (Join-Path $PSScriptRoot 'wms-api.ps1')
function Check([bool]$Condition,[string]$Message){if(!$Condition){throw $Message};Write-Host ('PASS|'+$Message)}
try {
 $user=Open-WmsSession $BaseUrl $CredentialFile $CaCert $ResolveHost
 Check (@($user.menus|Where-Object path -eq '/agent').Count -eq 1) 'agent menu'
 $status=Invoke-WmsApi '/api/v1/agent/status'
 Check (!$status.mailConfigured) 'mail disabled'
 $goods=@(Invoke-WmsApi '/api/v1/master-data/goods')
 $partners=@(Invoke-WmsApi '/api/v1/master-data/partners')
 $orders=@(Invoke-WmsApi '/api/v1/stockin')
 $orders=@($orders|Where-Object partnerCode -like 'FD-SUP-*'|ForEach-Object {Invoke-WmsApi "/api/v1/stockin/$($_.id)"})
 Check (@($goods|Where-Object code -like 'FD-*').Count -eq 10) '10 freight goods'
 Check (@($partners|Where-Object {$_.code -like 'FD-SUP-*' -and $_.type -eq 'SUPPLIER'}).Count -eq 5) '5 freight suppliers'
 Check (@($orders|Where-Object {$_.remark -like 'DEMO-FREIGHT-20261007*' -and $_.state -eq 'COMPLETED'}).Count -eq 15) '15 completed freight stock-ins'
 $receiptCount=0
 foreach($order in @($orders|Where-Object remark -like 'DEMO-FREIGHT-20261007*')){
  $receipts=@(Invoke-WmsApi "/api/v1/stockin/$($order.id)/receipts")
  $receiptCount+=$receipts.Count
  Check (@($receipts|Where-Object {!$_.remark.Contains('[simulated history]')}).Count -eq 0) ('marked simulated history '+$order.orderCode)
 }
 Check ($receiptCount -eq 15) '15 receipts, no duplicate import'
 $balances=@(Invoke-WmsApi '/api/v1/inventory/balances')
 Check (([decimal]($balances|Where-Object goodCode -eq 'FD-PLATE-COLD'|Select-Object -First 1).frozenQty) -eq 2) '2 bundles frozen'
 $util=Invoke-WmsApi '/api/v1/agent/utilization'
 Check ([decimal]$util.known_weight_kg -eq 683300) 'known weight 683300kg, bundles and tonnes counted once'
 foreach($case in @(@{code='FD-S02';percent=94.09},@{code='FD-S04';percent=111.11},@{code='FD-A01';percent=80})){
  $location=$util.locations|Where-Object code -eq $case.code|Select-Object -First 1
  Check ([Math]::Abs([decimal]$location.utilization_percent-[decimal]$case.percent) -le 0.01) ($case.code+' utilization '+$case.percent+'%')
 }
 Check (@($util.locations|Where-Object {$_.missing_weight_rows -gt 0 -and $null -ne $_.utilization_percent}).Count -eq 0) 'missing weight never fabricated'
 $jobs=@(Invoke-WmsApi '/api/v1/agent/schedules')
 $job=$jobs|Where-Object name -eq '每日库存巡检'|Select-Object -First 1
 if(!$job){throw 'Configure a patrol task before verification'}
 Check ($job.enabled -and $job.frequency -eq 'DAILY' -and $job.dailyTime -eq '08:00') 'daily 08:00 Beijing patrol enabled'
 $first=Invoke-WmsApi "/api/v1/agent/schedules/$($job.id)/run" 'POST' @{}
 $repeat=Invoke-WmsApi "/api/v1/agent/schedules/$($job.id)/run" 'POST' @{}
 Check ($first.findings -ge 6) 'freight risks detected'
 Check ($repeat.newNotifications -eq 0) 'repeat patrol deduplicated'
 $alerts=@(Invoke-WmsApi '/api/v1/agent/alerts')
 foreach($kind in @('LOW_STOCK','FROZEN_STOCK','CAPACITY','REPLENISHMENT')){Check (@($alerts|Where-Object {$_.state -eq 'OPEN' -and $_.kind -eq $kind}).Count -gt 0) ($kind+' station alert')}
 Check (@(Invoke-WmsApi '/api/v1/agent/mails').Count -eq 0) 'no mail queued or sent'
 if(!$SkipChat){
  foreach($case in @(@{q='找一下2026年10月5日前后入库的钢材，名称不记得了，按相关度展示候选和供应商';prefix='FD-';tool='search_receipts'},
       @{q='找一下2026年10月6日到的灰料，可能是粉煤灰，展示批次和供应商';prefix='FD-ASH-';tool='search_receipts'},
       @{q='分析当前仓库库位的承重利用率，指出超载和数据缺失';prefix='';tool='analyze_locations'})){
   $result=Invoke-WmsApi '/api/v1/agent/chat' 'POST' @{question=$case.q;previousQuestions=@()}
   Check ($result.state -eq 'COMPLETED') ('model completed: '+$case.q)
   $events=@($result.events|Where-Object tool -eq $case.tool)
   Check ($events.Count -gt 0) ('real tool used: '+$case.tool)
   foreach($event in $result.events){Check (@($event.result|Where-Object {$null -ne $_.error -and [string]$_.error -ne ''}).Count -eq 0) ('tool succeeded: '+$event.tool)}
   if($case.prefix){
    $rows=@($events|ForEach-Object {$_.result})
    Check (@($rows|Where-Object good_code -like ($case.prefix+'*')).Count -gt 0) 'real freight candidates returned'
    foreach($event in $events){
     $ranked=@($event.result)
     for($i=1;$i -lt $ranked.Count;$i++){if([int]$ranked[$i].relevance -gt [int]$ranked[$i-1].relevance){throw 'Candidates not relevance-ranked'}}
    }
   }
   Write-Host ('ANSWER|'+$result.answer)
  }
 }
 Write-Host 'VERIFY_FREIGHT_AGENT_OK'
} finally {Close-WmsSession}
