$ErrorActionPreference = 'Stop'
$script:TrustRoot = Split-Path $PSScriptRoot -Parent
$script:TrustPython = if ($env:TRUST_PYTHON) { $env:TRUST_PYTHON } else { 'python' }
function Get-TrustDeployment {
    $configJson = & $script:TrustPython (Join-Path $script:TrustRoot 'deploy/deployment.py') --format json
    if ($LASTEXITCODE -ne 0) { throw 'Deployment configuration failed validation' }
    return ($configJson | ConvertFrom-Json)
}
