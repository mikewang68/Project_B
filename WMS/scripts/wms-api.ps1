# Shared authenticated API helper. Keep credentials out of command output and Git.
$ErrorActionPreference='Stop'
function Open-WmsSession([string]$BaseUrl,[string]$CredentialFile,[string]$CaCert,[string]$ResolveHost) {
 if(!$BaseUrl){$BaseUrl='http://127.0.0.1:18081'}
 $script:WmsBase=$BaseUrl.TrimEnd('/')
 $script:WmsCookie=Join-Path ([IO.Path]::GetTempPath()) ('wms-demo-'+[guid]::NewGuid()+'.cookies')
 $curlCommand=Get-Command curl.exe -ErrorAction SilentlyContinue
 if(!$curlCommand){$curlCommand=Get-Command curl -ErrorAction Stop}
 $script:WmsCurl=$curlCommand.Source
 $script:WmsCommon=@('--noproxy','*','--silent','--show-error','--fail','--max-time','180')
 if($CaCert){$script:WmsCommon+=@('--cacert',$CaCert)}
 # Lab private CA has no reachable CRL. Keep hostname/chain checks enabled.
 if($CaCert -and $env:OS -eq 'Windows_NT'){$script:WmsCommon+=@('--ssl-revoke-best-effort')}
 if($ResolveHost){$script:WmsCommon+=@('--resolve',$ResolveHost)}
 $script:WmsCredentials=@{WMS_COMPANY=$env:WMS_COMPANY;WMS_USERNAME=$env:WMS_USERNAME;WMS_PASSWORD=$env:WMS_PASSWORD}
 if($CredentialFile){
  Get-Content -LiteralPath $CredentialFile -Encoding UTF8 | ForEach-Object {if($_ -match '^(WMS_COMPANY|WMS_USERNAME|WMS_PASSWORD)=(.*)$'){$script:WmsCredentials[$matches[1]]=$matches[2]}}
 }
 if(!$script:WmsCredentials.WMS_COMPANY){$script:WmsCredentials.WMS_COMPANY='default'}
 if(!$script:WmsCredentials.WMS_USERNAME -or !$script:WmsCredentials.WMS_PASSWORD){throw 'Provide WMS_USERNAME/WMS_PASSWORD environment variables or a private CredentialFile.'}
 $null=Invoke-WmsApi '/api/v1/auth/csrf'
 return Invoke-WmsApi '/api/v1/auth/login' 'POST' @{company=$script:WmsCredentials.WMS_COMPANY;username=$script:WmsCredentials.WMS_USERNAME;password=$script:WmsCredentials.WMS_PASSWORD}
}
function Invoke-WmsApi([string]$Path,[string]$Method='GET',$Body=$null) {
 $arguments=@('-b',$script:WmsCookie,'-c',$script:WmsCookie)
 if($Method -ne 'GET'){
  $token=Invoke-WmsApi '/api/v1/auth/csrf'
  $arguments+=@('-X',$Method,'-H',($token.headerName+': '+$token.token),'-H','Content-Type: application/json','--data-raw',($Body|ConvertTo-Json -Depth 15 -Compress))
 }
 $raw=& $script:WmsCurl @script:WmsCommon @arguments ($script:WmsBase+$Path)
 if($LASTEXITCODE -ne 0){throw "WMS request failed: $Method $Path"}
 $result=$raw|ConvertFrom-Json
 if($result.code -ne 'OK'){throw "WMS rejected request: $Method $Path ($($result.code))"}
 return $result.data
}
function Close-WmsSession {
 if($script:WmsCookie -and (Test-Path -LiteralPath $script:WmsCookie)){Remove-Item -LiteralPath $script:WmsCookie -Force}
 if($script:WmsCredentials){$script:WmsCredentials.Clear()}
}
