param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Company = 'default',
    [string]$Username = 'admin',
    [string]$Password = 'Admin@123456'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$live = Invoke-WebRequest -Uri "$BaseUrl/health/live" -UseBasicParsing -TimeoutSec 5
$ready = Invoke-WebRequest -Uri "$BaseUrl/health/ready" -UseBasicParsing -TimeoutSec 5
$csrf = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/csrf" -WebSession $session -Method Get
$headers = @{}; $headers[$csrf.data.headerName] = $csrf.data.token
$loginBody = @{ company=$Company; username=$Username; password=$Password } | ConvertTo-Json
$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -WebSession $session -Method Post -Headers $headers -ContentType 'application/json' -Body $loginBody
$checks = [ordered]@{
    live = $live.StatusCode
    ready = $ready.StatusCode
    login = $login.code
    dashboard = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/dashboard/summary" -WebSession $session).code
    inventory = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/inventory/balances" -WebSession $session).code
    stockin = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/stockin" -WebSession $session).code
    stockout = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/stockout" -WebSession $session).code
    finance = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/finance/summary" -WebSession $session).code
    integration = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/integration/tasks" -WebSession $session).code
    qimenBacktest = (Invoke-RestMethod -Uri "$BaseUrl/open/qimen/backtest").response.flag
}
$failed = $checks.GetEnumerator() | Where-Object { $_.Value -notin @(200, 'OK', 'success') }
$checks | ConvertTo-Json
if ($failed) { throw "冒烟测试失败：$(($failed.Name) -join ', ')" }
