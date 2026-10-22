# PSMS 千问接入

仅修改 PSMS。模型采用 `qwen3.8-27b`，中国内地（北京）百炼 OpenAI 兼容接口。

## 配置和验证

在 `PSMS/backend/.env.ai` 填写 `DASHSCOPE_API_KEY`，不要放入前端变量或提交到 Git。
此文件由后端读取，并被 `.env.*` 忽略规则排除。进程环境变量优先，其次 `.env.ai`，最后 `.env`。
接口地址默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型默认 `qwen3.8-27b`。
可使用 `AI_BASE_URL` 和 `AI_MODEL` 覆盖；原 `AI_API_KEY` 作为兼容后备。
配置修改后重启后端。

在 backend 目录运行 `pnpm ai:check`，直接调用模型验证凭据和模型权限；不会连接数据库、执行迁移或灌入种子。
`GET /api/ai/status` 仅表示配置是否存在，不能当成真实调用成功的证据。
UI 调用 `POST /api/ai/assist` 仍需要后端业务账号登录及 A 区权限。
配置可用时默认选中大模型分析，保留本地规则入口。

## 数据和界面

分析使用当前前端模拟计划、资源和工单摘要，不修改或执行业务数据。
请求包含 `dataSource: MOCK` 和 `snapshotAt` 演示时间，模型按演示时间理解计划。
界面不显示“通义千问 · 模拟数据”标签，结果标题为“AI 分析结果”。
后续改真实数据时，替换 AI 助手构造摘要的数据源，并同步调整后端 context 校验和系统提示。
当前接入采用非流式输出、关闭思考，30 秒超时；密钥、权限、模型地址、限流错误显示可操作提示。

## 官方参考

- [模型信息](https://help.aliyun.com/zh/model-studio/qwen3-8-27b)
- [OpenAI 兼容接口](https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope)
- [深度思考开关](https://help.aliyun.com/zh/model-studio/deep-thinking)

服务器已完成发布和真实调用验证，见 [服务器发布记录](2026-10-12-server-deployment.md)。

## 本机模拟界面运行

只启动前端时，`/api/ai/status` 会因为 3100 端口没有服务而返回 502，大模型分析选项会禁用。
当前模拟数据阶段，可在 backend 目录运行 `pnpm dev:ai`。
该入口仅在 development 环境运行，绑定 `127.0.0.1`，只提供 AI 接口及登录接口，不导入数据库。
打开 AI 助手时自动获取本地演示会话，不再显示账号密码输入框，直接点击“分析当前计划”。
本机 `dev:ai` 默认启用自动会话；服务器模拟数据演示可在后端环境设置 `AI_DEMO_AUTO_LOGIN=true` 启用。
会话接口为 `POST /api/ai/session`，要求专用请求头，签发一小时、仅限 AI 路由使用的 JWT。
AI 会话保存在助手组件内，不作为 CSV 入库等业务账号登录凭证。业务路由明确拒绝 AI 演示会话。
本机服务额外限定 Host/Origin 和会话签发频率；模型调用保留每分钟 10 次限制。
该服务不提供 CSV 入库等数据库功能。正式服务继续运行原 `pnpm dev` / `pnpm start` 入口及业务账号鉴权。
界面区分后端不可达与密钥未配置，并提供“重新检测”；检查最长等待 5 秒。

## 验证结果

2026-10-12：使用本机配置的密钥实际调用北京接口，`qwen3.8-27b` 成功返回模拟计划的准备建议、风险和下一步；没有连接业务数据库。
后端 20 项测试、AI 助手界面 2 项测试通过，前后端 TypeScript 检查及生产构建通过。
界面测试验证不显示品牌/数据标签、按演示时间发送 A 区摘要、后端不可用时保留规则分析。
已确认 `.env.ai` 被 Git 忽略，前端构建产物不含 API Key。

补充验证：Chrome 端到端通过本机前端登录后调用真实 `qwen3.8-27b`，成功显示分析结果。
运行 `node scripts/verify-ai.mjs` 可复验（需要前端和 `dev:ai` 均已运行，会实际调用模型）。
AI 助手界面测试增至 4 项，包含启动服务后的重新检测恢复和本地自动登录。
端到端验证自动登录、账号密码框隐藏及真实模型分析结果显示。
