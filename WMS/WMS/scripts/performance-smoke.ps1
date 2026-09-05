param(
    [string]$Url = 'http://127.0.0.1:8080/health/live',
    [ValidateRange(1,10000)][int]$Requests = 300,
    [ValidateRange(1,64)][int]$Concurrency = 12,
    [ValidateRange(1,10000)][int]$MaxP95Milliseconds = 500
)

$ErrorActionPreference = 'Stop'
$results = 1..$Requests | ForEach-Object -Parallel {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $using:Url -UseBasicParsing -TimeoutSec 5
        [pscustomobject]@{ Success=($response.StatusCode -eq 200); Milliseconds=$watch.Elapsed.TotalMilliseconds }
    } catch {
        [pscustomobject]@{ Success=$false; Milliseconds=$watch.Elapsed.TotalMilliseconds }
    }
} -ThrottleLimit $Concurrency
$ordered = @($results.Milliseconds | Sort-Object)
$p95 = $ordered[[Math]::Min($ordered.Count - 1, [Math]::Ceiling($ordered.Count * 0.95) - 1)]
$summary = [pscustomobject]@{
    Requests=$Requests; Concurrency=$Concurrency; Success=@($results | Where-Object Success).Count
    Failed=@($results | Where-Object { -not $_.Success }).Count
    AverageMilliseconds=[Math]::Round(($results.Milliseconds | Measure-Object -Average).Average,2)
    P95Milliseconds=[Math]::Round($p95,2); MaximumMilliseconds=[Math]::Round(($results.Milliseconds | Measure-Object -Maximum).Maximum,2)
}
$summary | ConvertTo-Json
if ($summary.Failed -gt 0 -or $summary.P95Milliseconds -gt $MaxP95Milliseconds) {
    throw "性能冒烟未达标：失败 $($summary.Failed)，P95 $($summary.P95Milliseconds)ms。"
}
