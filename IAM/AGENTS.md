# IAM 开发与公开内容边界

- 业务用户、组织、权限、会话与身份供给由本模块维护；TRUST 公用身份协议见 `../TRUST/docs/identity-openapi.json` 与 `identity-delivery.md`。
- 验收报告、工作汇报、部署回执、环境清单、账号信息、截图与脱敏摘要全部保存在本地 `.local/`，不提交 Git，也不复制到 PR 正文或评论。
- 公开文档保留通用安装、配置、架构与 API 说明；真实配置只放本地私有目录或服务器 `runtime/secrets/`。模板不能填写现场值后提交。
- 从仓库根目录执行 `python -B TRUST/scripts/check-identity-publication.py --base <最新远端main的SHA> --head HEAD`，检查最终树及全部待推送提交，并人工审查是否有不必要公开的内容。禁止强制添加私有文件绕过忽略规则。
- 运行状态与验收事实以本地私有证据为准，不能从公开 README 推断当前服务器状态。SYS/WMS 集成需相关维护方确认，不越权修改其他模块。
