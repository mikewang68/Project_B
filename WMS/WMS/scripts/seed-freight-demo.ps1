param([string]$BaseUrl=$env:WMS_BASE_URL,[string]$CredentialFile,[string]$CaCert=$env:WMS_CA_CERT,[string]$ResolveHost)
# Deliberate demo data import; never run against a production business database.
. (Join-Path $PSScriptRoot 'wms-api.ps1')
$data=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../database/demo/freight-demo.json') -Raw -Encoding UTF8|ConvertFrom-Json
$marker=$data.marker
function Ensure-Master([string]$Resource,[hashtable]$Item) {
 $existing=@(Invoke-WmsApi "/api/v1/master-data/$Resource")|Where-Object code -eq $Item.code|Select-Object -First 1
 if($existing){
  if(!$existing.remark -or !$existing.remark.StartsWith($marker)){throw "Code collision with non-demo data: $Resource/$($Item.code)"}
  Write-Output ('SKIP|'+$Resource+'/'+$Item.code) | Write-Host
  return $existing
 }
 $result=Invoke-WmsApi "/api/v1/master-data/$Resource" 'POST' $Item
 Write-Output ('ADD|'+$Resource+'/'+$Item.code) | Write-Host
 return $result
}
function Table($Row) { $item=@{};foreach($p in $Row.PSObject.Properties){$item[$p.Name]=$p.Value};return $item }
try {
 $user=Open-WmsSession $BaseUrl $CredentialFile $CaCert $ResolveHost
 Write-Host ('SCOPE|'+$user.tenant.currentWarehouse.code+'/'+$user.tenant.currentOwner.code)
 $defaults=@{status='ENABLED';remark=($marker+' 虚构货运演示，不用于实际作业')}
 foreach($row in $data.areas){$null=Ensure-Master 'areas' ($defaults+(Table $row))}
 $null=Ensure-Master 'work-areas' ($defaults+@{code='FD-RECEIVE';name='货运收货作业区（演示）';type='NORMAL'})
 foreach($row in $data.partners){$null=Ensure-Master 'partners' ($defaults+(Table $row)+@{address='虚构演示地址，不用于实际发运';telephone=''})}
 foreach($row in $data.categories){$null=Ensure-Master 'categories' ($defaults+(Table $row))}
 foreach($row in $data.locations){$null=Ensure-Master 'locations' ($defaults+(Table $row)+@{name=$row.code;type='L3';secondaryCode='FD-RECEIVE'})}
 $weights=@(Invoke-WmsApi '/api/v1/agent/weights')
 foreach($row in $data.goods){
  $item=Table $row;$item.Remove('weightKg')
  $good=Ensure-Master 'goods' ($defaults+$item+@{type='ZC'})
  $old=$weights|Where-Object code -eq $row.code|Select-Object -First 1
  if($good.unit -ne $row.unit){throw "Demo unit changed; refusing to overwrite: $($row.code)"}
  if(!$old -or [decimal]$old.weight_kg -le 0){$null=Invoke-WmsApi "/api/v1/agent/weights/$($good.id)" 'PUT' @{weightKg=$row.weightKg}}
 }
 $orders=@(Invoke-WmsApi '/api/v1/stockin')
 $demoOrders=@($orders|Where-Object partnerCode -like 'FD-SUP-*'|ForEach-Object {Invoke-WmsApi "/api/v1/stockin/$($_.id)"})
 foreach($row in $data.receipts){
  $existing=$demoOrders|Where-Object {$_.remark -and $_.remark.StartsWith($marker+' '+$row.key+' ')}|Select-Object -First 1
  if($existing){
   if($existing.state -ne 'COMPLETED'){throw "Partial demo receipt requires manual review: $($row.key)"}
   Write-Host ('SKIP|receipt/'+$row.key);continue
  }
  $good=$data.goods|Where-Object code -eq $row.goodCode|Select-Object -First 1
  $null=Invoke-WmsApi '/api/v1/stockin/quick' 'POST' @{
   inboundType='PURCHASE';goodCode=$row.goodCode;quantity=$row.quantity;locationCode=$row.locationCode
   partnerCode=$row.supplierCode;supplierCode=$row.supplierCode;qualityType='ZP';batchCode=$row.batchCode
   lpn=($marker+'-'+$row.key);unitPrice=$good.price;serialCodes=@()
   productDate=([datetime]$data.referenceDate).AddDays(-30).ToString('yyyy-MM-dd')
   remark=($marker+' '+$row.key+' 虚构演示收货');idempotencyKey=($marker+'-'+$row.key)
  }
  Write-Host ('RECEIPT|'+$row.key+'/'+$row.goodCode+'/'+$row.quantity)
 }
 $balances=@(Invoke-WmsApi '/api/v1/inventory/balances')
 $frozen=$balances|Where-Object {$_.goodCode -eq 'FD-PLATE-COLD' -and $_.batchCode -eq 'FD-20261003-PC-A'}|Select-Object -First 1
 if($frozen -and [decimal]$frozen.frozenQty -eq 0){
  $null=Invoke-WmsApi '/api/v1/inventory/freeze' 'POST' @{balanceId=$frozen.id;quantity=2;idempotencyKey=($marker+'-FREEZE');remark=($marker+' 冻结库存预警演示')}
 }
 $rules=@(Invoke-WmsApi '/api/v1/inventory/replenishment-rules')
 if(!($rules|Where-Object {$_.goodCode -eq 'FD-REBAR20' -and $_.locationCode -eq 'FD-S02'})){
  $null=Invoke-WmsApi '/api/v1/inventory/replenishment-rules' 'POST' @{goodCode='FD-REBAR20';locationCode='FD-S02';minQty=12;maxQty=20;status='ENABLED';remark=($marker+' 补货预警演示')}
 }
 Write-Host 'SEED_FREIGHT_OK|10 goods|5 suppliers|15 receipts|11 locations'
 Write-Host 'Historical demo timestamps require the separately reviewed SQL step; importing via API records the actual import time.'
} finally {Close-WmsSession}
