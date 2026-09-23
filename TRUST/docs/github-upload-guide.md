# 脱敏后上传 GitHub

本教程适用于现有 Project_B 工作区，远程 origin 已配置。原 PR #2 已由 PR #3 整体撤销；本次从撤销后的 main 重新准备，只包含 TRUST 和根目录 `.gitignore`，不修改 DTS、PSMS、SCS、WMS 等其他模块。准备阶段不提交、不推送、不改写 Git 历史，也不同步到运行服务器。

## 本次处理

- TRUST 私有部署配置、开发账号、日志、截图、运行数据、构建制品继续由忽略规则排除。
- 补充仓库级 `.local`、私钥和开发账号文件忽略规则。
- 仅在 TRUST 部署说明中将真实节点名、账户路径和域名替换为示例；地址使用文档示例网段。
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
python TRUST/scripts/check-publication.py --trust-only
git --no-pager diff --check
git --no-pager diff --stat
```

扫描结果应为 `0 findings`，范围明确显示 `TRUST and root .gitignore`。该结果不代表其他模块通过检查；省略 `--trust-only` 时仍扫描全仓库，其他模块的结果应另行交由负责人处理，不自动修改。脚本使用本地私有配置和账号文件比对真实值；其他电脑没有这些文件时，无法执行该项已知值比对。

确认私有文件被忽略：

```powershell
git check-ignore TRUST/.local/deployment.json TRUST/.local/development-accounts.json
git ls-files TRUST/.local TRUST/runtime
```

第一条应列出这些路径，第二条应没有输出。忽略规则不会自动移除已经被 Git 跟踪的文件；若第二条出现结果，先处理跟踪记录再上传。不要使用 `git add -f` 绕过忽略规则。

## 3. 创建提交分支

```powershell
git switch -c codex/trust-only-publication
```

本次准备工作已创建 `codex/trust-only-publication`，若当前已经在该分支，请跳过这一步。不要重新使用已合并并撤销的旧分支，也不要删除其他分支或强制覆盖。

## 4. 暂存并审查

确认 `git status --short` 中全部变更都是本次要上传的内容后执行：

```powershell
git add -- TRUST .gitignore
git --no-pager diff --cached --stat
git --no-pager -c core.quotepath=false diff --cached --name-only
git --no-pager diff --cached --check
git --no-pager diff --cached
python TRUST/scripts/check-publication.py --trust-only
```

暂存列表必须只有 `TRUST/` 下的文件和根目录 `.gitignore`，不能包含其他模块。确认没有私有 JSON、私钥、运行日志、截图证据、备份包或制品。限定路径的 `git add` 不会取消之前已经暂存的其他文件，因此仍必须检查完整暂存列表。

如果暂存了不应提交的文件，用下面的命令取消暂存，工作文件会保留：

```powershell
git restore --staged -- "具体文件路径"
```

扫描器遇到“已暂存后又修改”的文件会停止，避免检查的是新文件、提交的却是旧内容。审查最新版本后再次 `git add` 对应文件即可。

## 5. 提交并推送分支

```powershell
git commit -m "Restore TRUST appearance, portal and internal login without other module changes"
git push -u origin codex/trust-only-publication
```

GitHub 要求认证时，使用 Git Credential Manager 的浏览器登录流程，不要把令牌写进仓库或粘贴到远程 URL。若提示未配置提交身份，使用你自己的姓名和 GitHub 已验证邮箱或 GitHub 提供的 noreply 邮箱配置本仓库，然后重试提交。

如果 push 被拒绝，先保留错误输出并核对远程分支状态，不要使用 `--force`，也不要用 `reset --hard` 丢弃工作区。

## 6. 在 GitHub 合并

打开仓库，选择刚推送的分支，点击 `Compare & pull request`，目标分支选 `main`。检查 Files changed 中的文件与本地暂存清单一致，且只有 TRUST 与根目录 `.gitignore`，确认后创建并合并 PR。也可以先保留 PR 等待同事审查。不要对撤销 PR #3 再执行整体 Revert，否则其他模块的误改也会恢复。

本次使用 Git 提交，以便明确核对文件范围及忽略规则。

## 历史和检查边界

本次处理的是当前工作文件；早期提交里的旧节点名、旧文档和个人路径仍可能通过 Git 历史访问。`.gitignore`、新提交和删除文件都不会清除已有历史。本次没有强推或重写历史。

扫描不是完整秘密审计：跳过二进制及超过5 MB的文件，不解析图片、Office文档、压缩包，也不能识别所有供应商令牌或未知密码；历史检查仅核对敏感文件路径是否出现，未遍历全部历史文件内容。不要把 `0 findings` 理解为整个仓库历史绝无敏感信息。

若曾上传真实凭据，应先撤销或轮换，再另行安排历史清理；协作仓库清理历史需要协调，不能直接在本次上传命令中强推覆盖。

## 后续部署

本教程命令不执行服务器部署；仓库若另有自动部署流程，应按该流程核对。实际环境继续使用私有配置；开发快捷登录默认关闭，只能在获准的内网开发实例显式启用。上传源码不包含开发账号密码，克隆仓库后不会自动获得服务器账号。
