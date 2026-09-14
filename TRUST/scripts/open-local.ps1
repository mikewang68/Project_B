param([ValidateRange(1024,65535)][int]$Port=18180, [switch]$DryRun)
. "$PSScriptRoot/common.ps1"
$deployment = Get-TrustDeployment
if ($DryRun) { Write-Host 'Application tunnel configuration valid; no connection opened.'; return }
Write-Host "本机访问地址：http://127.0.0.1:$Port；关闭此命令会关闭访问通道。"
ssh -N -T -o BatchMode=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 -L "127.0.0.1:${Port}:127.0.0.1:28182" $deployment.nodes.application.sshAlias
if ($LASTEXITCODE -ne 0) { throw 'Application access tunnel failed; check trusted SSH configuration and forwarding policy' }
