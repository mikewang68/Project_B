param([switch]$Docker)
$ErrorActionPreference = 'Stop'
$auditRoot = Split-Path -Parent $PSScriptRoot
$auditJar = Join-Path $auditRoot 'service\target\audit-service-1.1.0.jar'
$clientsPath = Join-Path $auditRoot 'runtime\secrets\clients.json'
if (-not (Test-Path -LiteralPath $auditJar)) { throw 'Build AUDIT with Java 17: mvn install' }
if (-not (Test-Path -LiteralPath $clientsPath)) { throw 'Generate private clients first: python scripts/init-clients.py --domain demo --zone demo' }
Push-Location $auditRoot
try {
  if ($Docker) {
    # Listen only on the local computer; this profile uses H2, not the laboratory database.
    docker run --rm --name b-audit-local -p 127.0.0.1:18110:18110 -v "${auditRoot}:/audit" -w /audit `
      -e SPRING_PROFILES_ACTIVE=local -e AUDIT_BIND=0.0.0.0 -e AUDIT_CLIENTS_FILE=/audit/runtime/secrets/clients.json `
      maven:3.9.9-eclipse-temurin-17 java -jar /audit/service/target/audit-service-1.1.0.jar
  } else {
    $env:SPRING_PROFILES_ACTIVE='local'
    $env:AUDIT_CLIENTS_FILE=$clientsPath
    java -jar $auditJar
  }
} finally { Pop-Location }
