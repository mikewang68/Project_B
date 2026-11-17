import type { PlacementType } from '../placements'
import { MODEL_ROOT } from '@/config/runtime'

export type ModelLod = 'high' | 'medium' | 'low'

export interface ModelDescriptor {
  key: string
  type: PlacementType
  modelUrl: string
  lod: ModelLod
  minimumPixelSize?: number
  maximumScale?: number
  movableComponent?: 'frame' | 'trolley' | 'hoist' | 'cradle' | 'head'
}

const ROOT = MODEL_ROOT
const ASSET_VERSION = '?layout=20261117-dalian-v5-hd'
const glb = (name: string) => `${ROOT}${name}.glb${ASSET_VERSION}`

export const MODEL_MANIFEST: ModelDescriptor[] = [
  { key: 'site-shell', type: 'site_shell', modelUrl: glb('site-shell-v4-medium'), lod: 'medium', minimumPixelSize: 0, maximumScale: 1 },
  { key: 'core-environment', type: 'core_environment', modelUrl: glb('rebar-core-v4-medium'), lod: 'medium', minimumPixelSize: 0, maximumScale: 1 },
  { key: 'ash-environment', type: 'ash_environment', modelUrl: glb('ash-core-v4-medium'), lod: 'medium', minimumPixelSize: 0, maximumScale: 1 },

  // 40t / 30m 门式起重机（钢材线 & 散料线共用高精度 30m 跨门吊）
  { key: 'gantry-frame', type: 'gantry_crane', modelUrl: glb('rebar-gantry-frame-v4-medium'), lod: 'medium', minimumPixelSize: 32, maximumScale: 1, movableComponent: 'frame' },
  { key: 'gantry-trolley', type: 'gantry_crane', modelUrl: glb('rebar-gantry-trolley-v4-medium'), lod: 'medium', minimumPixelSize: 16, maximumScale: 1, movableComponent: 'trolley' },
  { key: 'gantry-hoist', type: 'gantry_crane', modelUrl: glb('rebar-gantry-hoist-v4-medium'), lod: 'medium', minimumPixelSize: 14, maximumScale: 1, movableComponent: 'hoist' },

  // 室内桥式起重机（S=28.5m：9台 20t 磁吸 + 4台 32t C型吊具）
  { key: 'bridge-frame', type: 'bridge_crane', modelUrl: glb('bridge-crane-frame-v4-medium'), lod: 'medium', minimumPixelSize: 20, maximumScale: 1, movableComponent: 'frame' },
  { key: 'bridge-trolley', type: 'bridge_crane', modelUrl: glb('bridge-crane-trolley-v4-medium'), lod: 'medium', minimumPixelSize: 12, maximumScale: 1, movableComponent: 'trolley' },
  { key: 'bridge-hoist-magnet', type: 'bridge_crane', modelUrl: glb('bridge-crane-magnet-hoist-v4-medium'), lod: 'medium', minimumPixelSize: 10, maximumScale: 1, movableComponent: 'hoist' },
  { key: 'bridge-hoist-chook', type: 'bridge_crane', modelUrl: glb('bridge-crane-chook-hoist-v4-medium'), lod: 'medium', minimumPixelSize: 10, maximumScale: 1, movableComponent: 'hoist' },

  // 移动式翻箱机（大车 + 0~135° 翻转框）
  { key: 'tipper-frame', type: 'mobile_tipper', modelUrl: glb('mobile-tipper-frame-v4-medium'), lod: 'medium', minimumPixelSize: 22, maximumScale: 1, movableComponent: 'frame' },
  { key: 'tipper-cradle', type: 'mobile_tipper', modelUrl: glb('mobile-tipper-cradle-v4-medium'), lod: 'medium', minimumPixelSize: 18, maximumScale: 1, movableComponent: 'cradle' },

  // 库底定量散装机（Z 轴伸缩对位溜管）
  { key: 'silo-loader-head', type: 'silo_loader', modelUrl: glb('silo-loader-head-v4-medium'), lod: 'medium', minimumPixelSize: 10, maximumScale: 1, movableComponent: 'head' },

  // 铁路平车、35t 散料集装箱、钢材货捆与公路货车/罐车
  { key: 'wagon', type: 'wagon', modelUrl: glb('rebar-wagon-v4-medium'), lod: 'medium', minimumPixelSize: 20, maximumScale: 1 },
  { key: 'bulk-container', type: 'bulk_container', modelUrl: glb('bulk-container-v4-medium'), lod: 'medium', minimumPixelSize: 16, maximumScale: 1 },
  { key: 'tank-truck', type: 'tank_truck', modelUrl: glb('tank-truck-v4-medium'), lod: 'medium', minimumPixelSize: 18, maximumScale: 1 },
  { key: 'road-truck', type: 'road_truck', modelUrl: glb('road-truck-v4-medium'), lod: 'medium', minimumPixelSize: 18, maximumScale: 1 },
  { key: 'steel-cargo', type: 'bulk_container', modelUrl: glb('steel-cargo-v4-medium'), lod: 'medium', minimumPixelSize: 10, maximumScale: 1 },
]

export function getModel(key: string): ModelDescriptor {
  const model = MODEL_MANIFEST.find((entry) => entry.key === key)
  if (!model) throw new Error(`Missing model manifest entry: ${key}`)
  return model
}
