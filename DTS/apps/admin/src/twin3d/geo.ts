/**
 * 坐标转换：中沙坝转运站（给大连大.dwg CGCS2000 统一偏移映射）→ 经纬度 / Cesium Cartesian3
 * 局部 CAD 坐标 = CGCS2000 坐标 - PIVOT_OFFSET_CGCS2000，兼顾施工图真实尺度与零 WebGL 浮点抖动
 */
import * as Cesium from 'cesium'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'

const M_PER_DEG_LAT = 110540
const M_PER_DEG_LON = (latDeg: number) => 111320 * Math.cos((latDeg * Math.PI) / 180)

export const PIVOT_OFFSET_CGCS2000: readonly [number, number] = SITE_LAYOUT_V4.pivotOffset

/** 场站地理基准原点（西藏林芝米林中沙坝转运站参考坐标） */
export const ORIGIN_LONLAT: [number, number] = [
  SITE_LAYOUT_V4.geoOrigin[0],
  SITE_LAYOUT_V4.geoOrigin[1],
]

/** 局部 CAD 坐标转 CGCS2000 平面坐标（米） */
export function localToCgcs2000(cadX: number, cadY: number): [number, number] {
  return [cadX + PIVOT_OFFSET_CGCS2000[0], cadY + PIVOT_OFFSET_CGCS2000[1]]
}

/** CGCS2000 平面坐标转局部 CAD 坐标（米） */
export function cgcs2000ToLocal(cgcsX: number, cgcsY: number): [number, number] {
  return [cgcsX - PIVOT_OFFSET_CGCS2000[0], cgcsY - PIVOT_OFFSET_CGCS2000[1]]
}

/** 获取中沙坝转运站场站几何中心经纬度 */
export function getYardCenterLonLat(): [number, number] {
  return cadToLonLat(SITE_LAYOUT_V4.siteShellCenter.cadX, SITE_LAYOUT_V4.siteShellCenter.cadY)
}

/** CAD 坐标 → 经纬度 */
export function cadToLonLat(
  cadX: number,
  cadY: number,
  origin: [number, number] = ORIGIN_LONLAT,
): [number, number] {
  const lon = origin[0] + cadX / M_PER_DEG_LON(origin[1])
  const lat = origin[1] + cadY / M_PER_DEG_LAT
  return [lon, lat]
}

/** CAD 坐标 → Cesium Cartesian3 */
export function cadToCartesian3(
  cadX: number,
  cadY: number,
  height = 0,
  origin: [number, number] = ORIGIN_LONLAT,
): Cesium.Cartesian3 {
  const [lon, lat] = cadToLonLat(cadX, cadY, origin)
  return Cesium.Cartesian3.fromDegrees(lon, lat, height)
}

/** Cesium Cartesian3 → 局部 CAD 坐标与高程 */
export function cartesian3ToCad(
  position: Cesium.Cartesian3,
  origin: [number, number] = ORIGIN_LONLAT,
): { cadX: number; cadY: number; height: number } {
  const carto = Cesium.Cartographic.fromCartesian(position)
  const lon = Cesium.Math.toDegrees(carto.longitude)
  const lat = Cesium.Math.toDegrees(carto.latitude)
  const cadX = (lon - origin[0]) * M_PER_DEG_LON(origin[1])
  const cadY = (lat - origin[1]) * M_PER_DEG_LAT
  return { cadX, cadY, height: carto.height }
}

type GeoJsonCoordinates = unknown[]

/**
 * CAD 导出的 yards.geojson 使用米制本地坐标，不是 WGS84 GeoJSON。
 * Cesium.GeoJsonDataSource 只接受经纬度坐标，因此必须在进入 Cesium 前转换。
 */
export function cadGeoJsonToWgs84<T>(geoJson: T, origin: [number, number] = ORIGIN_LONLAT): T {
  const transformCoordinates = (coordinates: GeoJsonCoordinates): GeoJsonCoordinates => {
    if (typeof coordinates[0] === 'number') {
      const cadX = coordinates[0]
      const cadY = coordinates[1]
      if (typeof cadX !== 'number' || typeof cadY !== 'number') return coordinates

      const [lon, lat] = cadToLonLat(cadX, cadY, origin)
      return coordinates.length === 3 ? [lon, lat, coordinates[2]] : [lon, lat]
    }

    return coordinates.filter(Array.isArray).map(transformCoordinates)
  }

  const clone = structuredClone(geoJson) as {
    features?: Array<{ geometry?: { coordinates?: GeoJsonCoordinates } }>
  }

  for (const feature of clone.features ?? []) {
    if (feature.geometry?.coordinates) {
      feature.geometry.coordinates = transformCoordinates(feature.geometry.coordinates)
    }
  }

  return clone as T
}
