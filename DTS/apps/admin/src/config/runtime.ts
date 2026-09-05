/**
 * 运行时统一配置（部署适配层）
 *
 * 所有与部署环境相关的地址集中在这里，业务/三维代码禁止再硬编码 '/models'、
 * '/data'、'/cesium'、'/api' 之类的绝对路径。
 *
 * 设计要点：
 * - 静态资源（GLB / GeoJSON / Cesium 离线包）一律跟随 Vite 的 base
 *   （import.meta.env.BASE_URL，构建时由 `base` 或 --base 决定），
 *   因此同一份产物无论被 Nginx 部署在根路径还是子路径（如 /dt/）都能正确加载。
 * - 后端 REST / WebSocket 入口走环境变量，默认同源相对路径，由 Nginx 反代到
 *   Easegress 网关；联调时可用完整地址覆盖。
 */

/** Vite 构建 base，统一保证以单个斜杠结尾，例如 '/' 或 '/dt/'。 */
export const APP_BASE: string = (import.meta.env.BASE_URL ?? '/').endsWith('/')
  ? import.meta.env.BASE_URL
  : `${import.meta.env.BASE_URL}/`

/**
 * 拼接挂在应用 base 下的静态资源路径。
 * @param relPath 相对 base 的路径，可带或不带前导斜杠，如 'models/a.glb'
 */
export function withBase(relPath: string): string {
  return `${APP_BASE}${relPath.replace(/^\/+/, '')}`
}

/**
 * 后端 REST 入口（对应服务器链路：浏览器 → Nginx → Easegress → 后端）。
 * 默认同源 '/api/v1'；可用 VITE_API_BASE_URL 覆盖为完整地址（跨域时由网关放行）。
 */
export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? '/api/v1').replace(/\/+$/, '')

/** WebSocket 入口；当前演示未启用，联调时通过 VITE_WS_URL 配置。 */
export const WS_URL: string = import.meta.env.VITE_WS_URL ?? ''

/** GLB 模型目录（末尾带斜杠）。 */
export const MODEL_ROOT: string = withBase('models/')

/** CAD 底图 GeoJSON。 */
export const CAD_GEOJSON_URL: string = withBase('data/yards.geojson')

/** Cesium 离线静态资源目录（Cesium 要求末尾必须带斜杠）。 */
export const CESIUM_BASE_URL: string = withBase('cesium/')
