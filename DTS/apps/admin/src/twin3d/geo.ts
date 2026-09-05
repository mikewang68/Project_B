/**
 * 坐标转换：CAD 本地坐标(米) → Cesium 经纬度
 * 与 packages/twin-3d/src/geo/coordTransform.ts 逻辑一致（为快速集成在此内联）
 */
import * as Cesium from 'cesium'

const M_PER_DEG_LAT = 110540
const M_PER_DEG_LON = (latDeg: number) => 111320 * Math.cos((latDeg * Math.PI) / 180)

/** 场站原点经纬度（占位，待确认真实坐标后替换） */
export const ORIGIN_LONLAT: [number, number] = [91.0, 29.0]

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
