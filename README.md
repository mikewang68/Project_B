# Project_B

数字孪生 / 综合监控一体化项目集合（多子系统合集）。

## 子系统

| 目录 | 说明 |
|------|------|
| `DTS/`  | 数字孪生系统（Digital Twin System） |
| `EHMS/` | 综合健康监测系统 |
| `EMCS/` | 能源管理与控制系统 |
| `IAM/`  | 身份与访问管理前端 |
| `PSMS/` | 生产调度管理系统 |
| `SCS/`  | 调度控制系统 |
| `WMS/`  | 仓储管理系统 |

## 快速开始

各子系统独立构建与运行，请进入对应目录查看 README。

```bash
# 示例：进入 IAM 前端
cd IAM && pnpm install && pnpm dev
```

## 注意事项

- 大型 CAD/模型/构建产物已通过 `.gitignore` 排除
- 各子目录保留各自原有的 `.gitignore` 规则
- 请勿提交 `.env` 等含密钥的文件
