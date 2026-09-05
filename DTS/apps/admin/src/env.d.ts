/// <reference types="vite/client" />

/** 自定义环境变量类型声明（BASE_URL/DEV/MODE/PROD 由 vite/client 提供）。 */
interface ImportMetaEnv {
  /** 后端 REST 入口，默认 /api/v1，可配为 Easegress 完整地址 */
  readonly VITE_API_BASE_URL?: string
  /** WebSocket 入口 */
  readonly VITE_WS_URL?: string
  /** 三维模型模式：procedural 时在 DEV 下使用程序化几何体兜底 */
  readonly VITE_TWIN_MODEL_MODE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
