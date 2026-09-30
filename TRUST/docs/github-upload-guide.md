# IAM/TRUST 公开内容与提交检查

公开仓库保存源码、测试程序、合成测试夹具、API/OpenAPI、依赖锁文件、无真实值的配置模板和通用使用说明。

验收报告、工作汇报、部署回执、现场资源清单、账号与网络信息、一次性管理脚本、截图、原始证据和脱敏报告全部留在本地 `.local/`。不要将这些内容复制进 PR 正文或评论。真实配置放 `.local/` 或服务器 `runtime/secrets/`。公开证书与 CRL 测试夹具必须明确不含生产身份或私钥。

## 提交前

确认工作范围、当前分支和远端主分支，再检查差异。以下命令从仓库根目录执行：

```powershell
git status --short
git fetch origin
git diff --name-only origin/main HEAD
git add -- IAM TRUST
git diff --cached --check
git diff --cached
```

人工检查不仅关注密码，也要检查文件是否有必要公开。`.gitignore` 不会移除已跟踪文件；不要使用强制添加绕过私有目录规则。提交后、推送前执行：

```powershell
python -B TRUST/scripts/check-identity-publication.py --base origin/main --head HEAD
```

扫描器检查最终 IAM/TRUST 文件树及全部待推送提交，检查凭据、私有路径、报告目录和本地配置中的环境标识。标识从 `.local/deployment.json` 和可选的 `.local/publication-identifiers.json`（字符串数组）读取，后者也不得上传。它也检查已在后续提交中删除的内容，不跳过二进制。没有本地私有配置的机器不能完成已知值比对；任意文件名下的工作汇报仍需人工识别。

旧 `check-publication.py --trust-only` 可辅助检查工作文件，但不检查历史，不能代替上述发布检查。

## PR 与历史

PR 说明只写具体代码行为、必要测试结论及未完成的集成边界，不上传现场报告。上传、合并和部署分别执行；上传分支不会自行切换服务入口。

删除当前文件或分支不会清除公开历史。确需整理发布历史时，先把原提交制作成本地 Git bundle 并执行 `git bundle verify`，同时保留资料副本，再核对远端 SHA。经授权仅重建自己的发布分支，使用绑定预期 SHA 的 `--force-with-lease` 防止覆盖他人的新提交；不得顺带改写共享 main 或其他模块。

扫描不覆盖继承的 main 历史、GitHub 旧 PR 引用、缓存或他人克隆。已泄露的凭据必须轮换；普通历史报告不能保证从全部副本消失。参考 [GitHub 历史清理说明](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)。
