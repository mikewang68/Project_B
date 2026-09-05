---
name: curl-auth-recipe
description: 骨架 CLI/curl 拿 Bearer token 的通用配方（transport crypto dev 关，captcha 从 Redis 直取）
metadata:
  type: reference
---

在 dev 环境用 curl 走完整登录 + 业务接口调用（无需浏览器、无需 puppeteer）。前提：
- backend `.env.dev` 里 `transport_crypto_enabled` 关（骨架 dev 缺省）
- backend 通过 `sys.account.captchaEnabled` Redis 键控 captcha；答案在 `captcha_codes:$uuid` 键
- Redis 容器名 `bdemo-redis`，DB 编号 `2`（骨架默认）

```bash
resp=$(curl -s http://localhost/dev-api/captchaImage)
uuid=$(echo "$resp" | python3 -c "import json,sys; print(json.load(sys.stdin)['uuid'])")
code=$(docker exec bdemo-redis redis-cli -n 2 get "captcha_codes:$uuid")
tok=$(curl -s -X POST http://localhost/dev-api/login \
  --data-urlencode 'username=admin' --data-urlencode 'password=admin123' \
  --data-urlencode "code=$code" --data-urlencode "uuid=$uuid" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
curl -s -H "Authorization: Bearer $tok" "http://localhost/dev-api/<endpoint>"
```

来源：team-lead 2026-07-14 联调裁决。全队通用。
