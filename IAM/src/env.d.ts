/// <reference types="vite/client" />

/** 自定义环境变量类型声明（BASE_URL/DEV/MODE/PROD 由 vite/client 提供）。 */
interface ImportMetaEnv {
  /** 后端 REST 入口，默认 /api/v1，可配置为网关完整地址 */
  readonly VITE_API_BASE_URL?: string
  /** 本地开发 /api 代理目标 */
  readonly VITE_DEV_API_TARGET?: string
  /** 子路径部署前缀，如 /iam/ */
  readonly VITE_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
