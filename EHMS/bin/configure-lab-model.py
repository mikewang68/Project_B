#!/usr/bin/env python3
"""Interactive server-only model setup. Never print or pass a key through argv."""
import argparse
import datetime
import getpass
import json
import os
from pathlib import Path
import re
import shlex
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def call_api(base, path, key, payload=None):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(base + path, data=body, headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json"})
    # Direct laboratory connection only. Do not send keys to a redirect or a workstation proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        reasons = {401: "Key无效", 403: "Key或模型没有访问权限", 404: "接口路径或模型ID不正确",
                   429: "模型限流，请稍后再试"}
        raise ValueError("接口验证失败（HTTP %s）：%s" % (error.code, reasons.get(error.code, "请联系模型服务负责人"))) from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        raise ValueError("模型服务网络或响应格式异常，请检查服务器到模型接口的连接") from None


def normalize_base(value):
    parsed = urllib.parse.urlsplit(value.strip().rstrip("/"))
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("Base URL格式不正确，不能包含账号、参数或密钥")
    # Also validate the port before any authenticated request.
    parsed.port
    path = parsed.path.rstrip("/")
    if path.endswith("/chat/completions"):
        path = path[:-len("/chat/completions")]
    if not path:
        path = "/v1"
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, path, "", ""))


def choose_model(response, requested="", choose=None):
    choose = choose or input
    rows = response.get("data", []) if isinstance(response, dict) else []
    ids = list(dict.fromkeys(row.get("id") for row in rows if isinstance(row, dict)
                            and isinstance(row.get("id"), str) and row.get("id")))
    if not ids:
        raise ValueError("/models 未返回可用模型ID，请联系实验室模型服务负责人")
    if requested:
        if requested not in ids:
            raise ValueError("指定的模型ID不在 /models 返回的列表中，未修改配置")
        return requested
    print("服务返回的模型ID：")
    for index, model in enumerate(ids, 1):
        # Remove terminal control characters from untrusted server metadata.
        print("  %d. %s" % (index, re.sub(r"[^\x20-\x7e\u0080-\uffff]", "", model)))
    if len(ids) == 1:
        if choose("仅有一个模型，使用此模型？[Y/n] ").strip().lower() in ("n", "no"):
            raise ValueError("已取消，原配置不变")
        return ids[0]
    selected = choose("输入模型序号（千问3请选相应Qwen3条目）：").strip()
    if not selected.isdigit() or not 1 <= int(selected) <= len(ids):
        raise ValueError("无效序号，原配置不变")
    return ids[int(selected) - 1]


def atomic_private_write(path, content):
    fd, name = tempfile.mkstemp(prefix=".ehm-ai-", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(name, str(path))
    finally:
        if os.path.exists(name):
            os.unlink(name)


def save_config(directory, base, key, model, tools=False):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    # Versioned key ensures an interrupted setup cannot replace the running provider's key.
    key_path = directory / (".lab-model-api-key-" + stamp)
    env_path = directory / "ehm-ai.env"
    origin = urllib.parse.urlsplit(base)
    settings = {
        "EHM_AI_ENABLED": "true", "EHM_AI_PROVIDER": "实验室千问",
        "EHM_AI_BASE_URL": base, "EHM_AI_MODEL": model, "EHM_AI_API_KEY": "",
        "EHM_AI_API_KEY_FILE": str(key_path), "EHM_AI_DATA_POLICY": "anonymized",
        "EHM_AI_TIMEOUT_SECONDS": "45", "EHM_AI_TOOLS_ENABLED": str(tools).lower(),
        "EHM_AI_TRUSTED_HTTP_ORIGIN": (origin.scheme + "://" + origin.netloc) if origin.scheme == "http" else "",
    }
    backup = None
    if env_path.exists():
        backup = directory / ("ehm-ai.env.bak-" + stamp)
        atomic_private_write(backup, env_path.read_text(encoding="utf-8"))
    atomic_private_write(key_path, key + "\n")
    atomic_private_write(env_path, "".join(name + "=" + shlex.quote(value) + "\n" for name, value in settings.items()))
    return env_path, backup


def main():
    parser = argparse.ArgumentParser(description="将EHM切换到实验室OpenAI兼容模型接口（Key隐藏输入）")
    parser.add_argument("--base-url", required=True,
                        help="填写由模型服务负责人确认的真实OpenAI兼容接口，例如https://model.example.internal/v1")
    parser.add_argument("--config-dir", default=os.environ.get("EHM_RUNTIME_CONFIG_DIR", "/opt/b-project/ehm/config"))
    parser.add_argument("--model", default="", help="可选；必须与/models返回的ID完全一致")
    parser.add_argument("--tools-enabled", action="store_true", help="仅在实验室确认模型支持function calling后启用")
    args = parser.parse_args()
    if not sys.stdin.isatty():
        raise ValueError("必须在交互式终端输入Key，不支持管道、命令行参数或脚本中硬编码Key")
    base = normalize_base(args.base_url)
    if base.startswith("http://"):
        print("使用指定实验室HTTP接口；仅允许该主机和端口。请确保链路位于受控内网/Tailscale网络。")
    key = getpass.getpass("完整实验室 API Key（输入隐藏，不回显）：").strip()
    if not key or any(char.isspace() for char in key) or "*" in key:
        raise ValueError("请输入完整Key，不能含空白或打码的星号；原配置不变")
    model = choose_model(call_api(base, "/models", key), args.model)
    print("正在用无业务数据的短消息测试选定模型…")
    response = call_api(base, "/chat/completions", key, {
        "model": model, "messages": [{"role": "user", "content": "这是一条连通性测试，不含业务数据。只回复：连接成功。/no_think"}],
        "stream": False, "max_tokens": 512,
    })
    try:
        content = response["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError):
        raise ValueError("模型返回格式不符合chat/completions规范，原配置不变") from None
    if not isinstance(content, str) or not content.strip():
        raise ValueError("接口接受了请求但未返回回答正文，请联系负责人确认千问思考模式配置；原配置不变")
    if args.tools_enabled:
        probe = call_api(base, "/chat/completions", key, {
            "model": model, "messages": [{"role": "user", "content": "请调用probe_connection函数。这是无业务数据的兼容性测试。/no_think"}],
            "tools": [{"type": "function", "function": {"name": "probe_connection", "description": "只验证工具调用格式，不执行任何动作", "parameters": {"type": "object", "properties": {}, "additionalProperties": False}}}],
            "tool_choice": {"type": "function", "function": {"name": "probe_connection"}},
            "stream": False, "max_tokens": 256,
        })
        try:
            calls = probe["choices"][0]["message"]["tool_calls"]
            valid = bool(calls) and calls[0]["function"]["name"] == "probe_connection" and bool(calls[0]["id"])
            json.loads(calls[0]["function"]["arguments"])
        except (KeyError, IndexError, TypeError, ValueError):
            valid = False
        if not valid:
            raise ValueError("模型未通过function calling格式测试，请去掉--tools-enabled后重试；原配置不变")
    if input("模型验证成功。保存配置替换DeepSeek？[y/N] ").strip().lower() not in ("y", "yes"):
        print("已取消，原配置不变。")
        return
    env_path, backup = save_config(Path(args.config_dir).expanduser().resolve(), base, key, model, args.tools_enabled)
    key = ""
    print("已保存私有模型配置：" + str(env_path))
    if backup:
        print("旧配置备份：" + str(backup))
    print("请按接入说明重启EHM后端。浏览器无需配置Key；默认只发送脱敏数据，不执行数据库写入或设备控制。")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print("配置未完成：" + str(error), file=sys.stderr)
        sys.exit(1)
    except (KeyboardInterrupt, EOFError):
        print("\n已取消。", file=sys.stderr)
        sys.exit(1)
