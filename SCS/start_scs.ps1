# SCS 系统一键启动脚本
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if (Test-Path (Join-Path $ScriptDir "Project_B\SCS")) {
    $ScsDir = Join-Path $ScriptDir "Project_B\SCS"
} elseif ((Test-Path (Join-Path $ScriptDir "backend")) -and (Test-Path (Join-Path $ScriptDir "frontend"))) {
    $ScsDir = $ScriptDir
} else {
    $ScsDir = "c:\Users\xis\Desktop\项目开发\Project_B\SCS"
}

$BackendDir = Join-Path $ScsDir "backend"
$FrontendDir = Join-Path $ScsDir "frontend"
$MavenBin = Join-Path $ScsDir ".tools\apache-maven-3.9.16\bin\mvn.cmd"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  正在一键启动 SCS (智慧货场装卸作业安全卡控系统)..." -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Cyan

if (Test-Path $MavenBin) {
    $MavenCmd = "& '$MavenBin'"
} else {
    $MavenCmd = "mvn"
}

# 1. 启动后端服务 (Spring Boot 8080)
Write-Host "[1/2] 正在启动后端服务 (Spring Boot 8080)..." -ForegroundColor Yellow
$BackendCmd = "Set-Location '$BackendDir'; $MavenCmd -gs .mvn/settings.xml -s .mvn/settings.xml spring-boot:run"
Start-Process powershell -ArgumentList "-NoExit", "-Command", $BackendCmd

# 2. 启动前端服务 (Vite 5173)
Write-Host "[2/2] 正在启动前端服务 (Vite 5173)..." -ForegroundColor Yellow
$FrontendCmd = "Set-Location '$FrontendDir'; pnpm dev"
Start-Process powershell -ArgumentList "-NoExit", "-Command", $FrontendCmd

Write-Host ""
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "  后端与前端已在独立终端窗口启动！" -ForegroundColor Green
Write-Host "  前端管理控制台: http://localhost:5173/" -ForegroundColor White
Write-Host "  后端健康探针:   http://localhost:8080/health/live" -ForegroundColor White
Write-Host "  后端接口文档:   http://localhost:8080/swagger-ui/index.html" -ForegroundColor White
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "提示：保持打开的两个服务窗口运行即可；关闭对应窗口即可停止服务。" -ForegroundColor Gray
Start-Sleep -Seconds 3
