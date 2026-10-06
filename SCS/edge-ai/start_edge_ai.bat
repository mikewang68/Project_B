@echo off
chcp 65001 >nul
echo ========================================================
echo  SCS Edge AI Vision Inference Service Launcher
echo  E-Drive Storage Governance Protocol Active
echo ========================================================

:: 1. E-Drive Storage Governance Environment Variables
set HF_HOME=E:\AI_Cache\huggingface
set HUGGINGFACE_HUB_CACHE=E:\AI_Cache\huggingface\hub
set TRANSFORMERS_CACHE=E:\AI_Cache\transformers
set TORCH_HOME=E:\AI_Cache\torch
set PIP_CACHE_DIR=E:\AI_Cache\pip
set TEMP=E:\AI_Cache\temp
set TMP=E:\AI_Cache\temp

:: 2. Target SCS Backend URL (Configurable, defaults to Node4 or local)
if "%SCS_BACKEND_URL%"=="" (
    set SCS_BACKEND_URL=http://127.0.0.1:18080
)

echo [INFO] AI Storage Base: E:\AI_Cache
echo [INFO] Central SCS Backend: %SCS_BACKEND_URL%
echo [INFO] Starting FastAPI service on port 18090...

cd /d "%~dp0"
python -m uvicorn main:app --host 0.0.0.0 --port 18090
