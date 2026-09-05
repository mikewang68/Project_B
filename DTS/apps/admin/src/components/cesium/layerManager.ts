/**
 * 图层管理器：CAD 图层 → Cesium 图层开关（需求模块 A：图层管理）
 * 从 layers.json / yards.geojson 派生前端图层配置
 */
import * as Cesium from 'cesium'
import { withBase } from '@/config/runtime'

export interface LayerDef {
  id: string
  name: string
  url: string
  visible: boolean
  style: {
    color: string
    width: number
  }
  /** 对应需求模块A的图层类型 */
  category: 'track' | 'area' | 'equipment' | 'person' | 'fence'
}

/**
 * 从 yards.geojson 加载股道/区域图层（CAD 落位）
 */
export function buildCadLayers(baseUrl = withBase('data')): LayerDef[] {
  return [
    {
      id: 'cad-track',
      name: '股道',
      url: `${baseUrl}/yards.geojson`,
      visible: true,
      style: { color: '#00B4D8', width: 2 },
      category: 'track',
    },
    {
      id: 'cad-area',
      name: '区域边界',
      url: `${baseUrl}/yards.geojson`,
      visible: true,
      style: { color: '#F4A261', width: 1.5 },
      category: 'area',
    },
  ]
}

/**
 * 按类型过滤并高亮图层中的要素
 * GeoJSON 按 properties.category 区分 track / area
 */
export async function filterByCategory(
  dataSource: Cesium.GeoJsonDataSource,
  category: string,
  show: boolean,
): Promise<void> {
  const entities = dataSource.entities.values
  for (const entity of entities) {
    const cat = entity.properties?.category?.getValue?.() ?? ''
    if (cat === category) {
      entity.show = show
    }
  }
}

/**
 * 清空所有 CAD 图层
 */
export function clearAllLayers(viewer: Cesium.Viewer): void {
  viewer.dataSources.removeAll()
}
