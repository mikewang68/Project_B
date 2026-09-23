# 脱敏后上传 GitHub

本教程适用于现有 Project_B 工作区，远程 origin 已配置。本次只修改本地待提交内容，没有提交、推送、改写 Git 历史或同步脱敏模板到运行服务器。

## 本次处理

- TRUST 私有部署配置、开发账号、日志、截图、运行数据、构建制品继续由忽略规则排除。
- 补充仓库级 `.local`、私钥和开发账号文件忽略规则。
- 部署说明中的真实节点名、账户路径和域名替换为示例；地址使用文档示例网段。其他系统已有说明中的对应信息一并替换。
- DTS 远程发布脚本要求显式指定目标主机；SCS 调试脚本改用仓库相对路径。
- 固定实际环境的一次性 TRUST 迁移脚本移至本地私有目录，不上传。
- 新增 `TRUST/scripts/check-publication.py`，检查 Git 上传候选文件中的已知私有部署标识、真实开发密码、常见令牌、私钥内容和私有路径。只报告位置，不打印秘密值。

公开文档中的 `project.example.com`、`192.0.2.*`、`198.51.100.*` 和 `trustdev` 是示例，不可直接当作运行环境使用。运行服务器未被这些替换修改。

## 1. 打开已有仓库

在 PowerShell 中进入你实际保存 Project_B 的目录，例如：

```powershell
cd C:\workspace\projects\Project_B
git status --short
git branch --show-current
git remote -v
```

此处目录仅为示例，请替换成实际路径。确认 origin 指向你的 Project_B 仓库。不要重新执行 `git init`，也不用重复添加 origin。

## 2. 扫描待上传内容

```powershell
python TRUST/scripts/check-publication.py
git diff --check
git diff --stat
```

扫描结果应为 `0 findings`。如果发现问题，按文件路径检查，处理后重新扫描。脚本使用本地私有配置和账号文件比对真实值；其他电脑没有这些文件时，无法执行该项已知值比对。

确认私有文件被忽略：

```powershell
git check-ignore TRUST/.local/deployment.json TRUST/.local/development-accounts.json
git ls-files TRUST/.local TRUST/runtime
```

第一条应列出这些路径，第二条应没有输出。忽略规则不会自动移除已经被 Git 跟踪的文件；若第二条出现结果，先处理跟踪记录再上传。不要使用 `git add -f` 绕过忽略规则。

## 3. 创建提交分支

```powershell
git switch -c codex/trust-publication
```

如果这个分支已存在，先确认它是否就是本次使用的分支；不要删除其他分支或强制覆盖。

## 4. 暂存并审查

确认 `git status --short` 中全部变更都是本次要上传的内容后执行：

```powershell
git add -A
git diff --cached --stat
git diff --cached --name-only
git diff --cached
python TRUST/scripts/check-publication.py
```

长差异视图按 `q` 退出。确认暂存列表没有私有 JSON、私钥、运行日志、截图证据、备份包或制品。此次包含其他子系统文档脱敏，以及 DTS 两个文件的改名；它们并非业务代码删除。

如果暂存了不应提交的文件，用下面的命令取消暂存，工作文件会保留：

```powershell
git restore --staged -- "具体文件路径"
```

扫描器遇到“已暂存后又修改”的文件会停止，避免检查的是新文件、提交的却是旧内容。审查最新版本后再次 `git add` 对应文件即可。

## 5. 提交并推送分支

```powershell
git commit -m "Publish TRUST appearance and internal development login; sanitize deployment examples"
git push -u origin codex/trust-publication
```

GitHub 要求认证时，使用 Git Credential Manager 的浏览器登录流程，不要把令牌写进仓库或粘贴到远程 URL。若提示未配置提交身份，使用你自己的姓名和 GitHub 已验证邮箱或 GitHub 提供的 noreply 邮箱配置本仓库，然后重试提交。

如果 push 被拒绝，先保留错误输出并核对远程分支状态，不要使用 `--force`，也不要用 `reset --hard` 丢弃工作区。

## 6. 在 GitHub 合并

打开仓库，选择刚推送的分支，点击 `Compare & pull request`，目标分支选 `main`。检查 Files changed 中的文件与本地暂存清单一致，确认后创建并合并 PR。也可以先保留 PR 等待同事审查。

GitHub 网页上传文件不适合这次更新：本次有改名、删除和忽略文件边界，使用 Git 更容易保留这些关系。

## 历史和检查边界

本次处理的是当前工作文件；早期提交里的旧节点名、旧文档和个人路径仍可能通过 Git 历史访问。`.gitignore`、新提交和删除文件都不会清除已有历史。本次没有强推或重写历史。

扫描不是完整秘密审计：跳过二进制及超过5 MB的文件，不解析图片、Office文档、压缩包，也不能识别所有供应商令牌或未知密码；历史检查仅核对敏感文件路径是否出现，未遍历全部历史文件内容。不要把 `0 findings` 理解为整个仓库历史绝无敏感信息。

若曾上传真实凭据，应先撤销或轮换，再另行安排历史清理；协作仓库清理历史需要协调，不能直接在本次上传命令中强推覆盖。

## 后续部署

GitHub 上传不会自动更新服务器。实际环境继续使用私有配置；开发快捷登录默认关闭，只能在获准的内网开发实例显式启用。上传源码不包含开发账号密码，克隆仓库后不会自动获得服务器账号。
