#!/usr/bin/env bash
# node6 路径 B（unified SCS @20986）发布：换后端 jar + 换前端静态文件。
# 由 jingchanglong 通过 sudo 执行；先备份 unit 与前端目录，健康检查失败自动回滚 unit。
set -euo pipefail

REL="${1:?need release dir}"
COMP=/opt/project-b-unified/components/scs-37ecf694fb553a81e45ec1e5d1371ec570ac90a8
UNIT=/etc/systemd/system/project-b-unified-scs.service
WEB=/var/www/project-b-unified/components/scs-web-d5b6672f88955c4e5ef009fae7d09b27eb658d55
STAMP=$(date +%Y%m%d_%H%M%S)
BAK=/var/backups/project-b-unified/"$STAMP"
mkdir -p "$BAK"

JAR_SHA=$(sha256sum "$REL/backend/safety-gate-service.jar" | cut -c1-8)
JAR="$COMP/person-position-$JAR_SHA.jar"
cp "$REL/backend/safety-gate-service.jar" "$JAR"
chmod 644 "$JAR"
echo "[1] jar -> $JAR ($(stat -c%s "$JAR") bytes)"

cp -a "$UNIT" "$BAK/project-b-unified-scs.service"
sed -i "s#^ExecStart=.*-jar [^ ]*#ExecStart=/opt/java17/bin/java -Xms64m -Xmx256m -jar $JAR#" "$UNIT"
grep '^ExecStart=' "$UNIT"

tar -czf "$BAK/scs-web.tar.gz" -C "$WEB" .
echo "[2] web backed up -> $BAK/scs-web.tar.gz"

systemctl daemon-reload
systemctl restart project-b-unified-scs
code=""
for i in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:20986/health/ready || true)
  if [ "$code" = "200" ]; then echo "[3] health 200 (try $i)"; break; fi
  sleep 2
done
if [ "$code" != "200" ]; then
  echo "[3] HEALTH FAILED ($code) -> rollback unit"
  cp -a "$BAK/project-b-unified-scs.service" "$UNIT"
  systemctl daemon-reload
  systemctl restart project-b-unified-scs
  exit 1
fi

find "$WEB" -mindepth 1 -delete
cp -a "$REL/frontend/." "$WEB/"
chown -R root:root "$WEB"
echo "[4] web -> $(ls "$WEB" | tr '\n' ' ')"
grep -oE 'index-[^"]+\.js' "$WEB/index.html" | head -1

echo "[5] anon api: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:20986/api/v1/overview/summary)"
echo "[6] done. rollback point: $BAK"
