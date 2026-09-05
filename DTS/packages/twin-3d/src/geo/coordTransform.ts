/**
 * 坐标转换模块：CAD 本地坐标(米) ↔ Cesium 经纬度
 *
 * CAD 图纸使用局部直角坐标系（单位米），Cesium 使用 WGS84 经纬度。
 * 转换方式：定义场站原点经纬度 + CAD 原点坐标 + 旋转角，做 平移→旋转→缩放。
 *
 * 使用前需确认：
 *  - originLonLat：场站中心真实经纬度（需从甲方/地图获取，当前为占位值）
 *  - cadOrigin：CAD 原点坐标（通常为 (0,0)）
 *  - rotationDeg：CAD 坐标系相对正北的旋转角（从图纸指北针读取，默认 0）
 */
import * as Cesium from 'cesium'

export interface CadTransformConfig {
  /** 场站中心经纬度 [经度, 纬度]，需现场确认 */
  originLonLat: [number, number]
  /** CAD 原点坐标 [x, y]（米） */
  cadOrigin: [number, number]
  /** CAD 坐标系相对正北的旋转角（度） */
  rotationDeg: number
}

export const DEFAULT_TRANSFORM: CadTransformConfig = {
  // TODO: 从 train.dwg / 甲方确认场站真实经纬度（西藏雅鲁藏布江流域）
  originLonLat: [91.0, 29.0],
  cadOrigin: [0, 0],
  rotationDeg: 0,
}

// 每度纬度 ≈ 110.54km，每度经度随纬度缩放
const M_PER_DEG_LAT = 110540
const M_PER_DEG_LON = (latDeg: number) => 111320 * Math.cos((latDeg * Math.PI) / 180)

/**
 * CAD 本地坐标 → Cesium 经纬度
 * @param cadX CAD X 坐标（米）
 * @param cadY CAD Y 坐标（米）
 */
export function cadToLonLat(
  cadX: number,
  cadY: number,
  cfg: CadTransformConfig = DEFAULT_TRANSFORM,
): [number, number] {
  // 1. 相对 CAD 原点平移
  let dx = cadX - cfg.cadOrigin[0]
  let dy = cadY - cfg.cadOrigin[1]

  // 2. 旋转（CAD 坐标轴旋转到正北对齐）
  if (cfg.rotationDeg !== 0) {
    const rad = (cfg.rotationDeg * Math.PI) / 180
    const cos = Math.cos(rad)
    const sin = Math.sin(rad)
    // 旋转矩阵
    const nx = dx * cos - dy * sin
    const ny = dx * sin + dy * cos
    dx = nx
    dy = ny
  }

  // 3. 米 → 度，叠加场站原点
  const lon = cfg.originLonLat[0] + dx / M_PER_DEG_LON(cfg.originLonLat[1])
  const lat = cfg.originLonLat[1] + dy / M_PER_DEG_LAT

  return [lon, lat]
}

/**
 * Cesium 经纬度 → CAD 本地坐标（用于反向定位/校验）
 */
export function lonLatToCad(
  lon: number,
  lat: number,
  cfg: CadTransformConfig = DEFAULT_TRANSFORM,
): [number, number] {
  // 1. 度 → 米
  let dx = (lon - cfg.originLonLat[0]) * M_PER_DEG_LON(cfg.originLonLat[1])
  let dy = (lat - cfg.originLonLat[1]) * M_PER_DEG_LAT

  // 2. 逆旋转
  if (cfg.rotationDeg !== 0) {
    const rad = (-cfg.rotationDeg * Math.PI) / 180
    const cos = Math.cos(rad)
    const sin = Math.sin(rad)
    const nx = dx * cos - dy * sin
    const ny = dx * sin + dy * cos
    dx = nx
    dy = ny
  }

  // 3. 加 CAD 原点
  return [dx + cfg.cadOrigin[0], dy + cfg.cadOrigin[1]]
}

/** 转成 Cesium Cartesian3（供 Cesium 直接使用） */
export function cadToCartesian3(
  cadX: number,
  cadY: number,
  height = 0,
  cfg: CadTransformConfig = DEFAULT_TRANSFORM,
): Cesium.Cartesian3 {
  const [lon, lat] = cadToLonLat(cadX, cadY, cfg)
  return Cesium.Cartesian3.fromDegrees(lon, lat, height)
}
