@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
where py >nul 2>&1
if not errorlevel 1 (
  py -3 -X utf8 "%~dp0simulator.py" serve --config "%~dp0config.example.json" --open-browser
  goto finished
)
where python >nul 2>&1
if not errorlevel 1 (
  python -X utf8 "%~dp0simulator.py" serve --config "%~dp0config.example.json" --open-browser
  goto finished
)
echo 未找到 Python 3.11 或更高版本。请安装后重新启动。
:finished
echo.
echo 服务已停止。演练数据保存在 simulator\runtime，未修改正式 WMS。
pause
endlocal
