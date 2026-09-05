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
  movableComponent?: 'frame' | 'trolley' | 'hoist'
}

// Cache-bust the corrected CAD-X-aligned production assets.  The suffix is
// intentionally centralized here so a browser cannot silently reuse the
// previous pre-axis-fix GLBs.
// 模型目录跟随部署 base（见 @/config/runtime），避免子路径部署时 404。
const ROOT = MODEL_ROOT
const ASSET_VERSION = '?layout=20260813-axis-fix'

/** 默认使用 medium LOD；high/low 是离线可替换资产，不从网络加载。 */
export const MODEL_MANIFEST: ModelDescriptor[] = [
  { key: 'site-shell', type: 'site_shell', modelUrl: `${ROOT}site-shell-v1-medium.glb${ASSET_VERSION}`, lod: 'medium' },
  { key: 'core-environment', type: 'core_environment', modelUrl: `${ROOT}rebar-core-v3-medium.glb${ASSET_VERSION}`, lod: 'medium' },
  { key: 'gantry-frame', type: 'gantry_crane', modelUrl: `${ROOT}rebar-gantry-frame-v3-medium.glb${ASSET_VERSION}`, lod: 'medium', movableComponent: 'frame' },
  { key: 'gantry-trolley', type: 'gantry_crane', modelUrl: `${ROOT}rebar-gantry-trolley-v3-medium.glb${ASSET_VERSION}`, lod: 'medium', movableComponent: 'trolley', minimumPixelSize: 18 },
  { key: 'gantry-hoist', type: 'gantry_crane', modelUrl: `${ROOT}rebar-gantry-hoist-v3-medium.glb${ASSET_VERSION}`, lod: 'medium', movableComponent: 'hoist', minimumPixelSize: 18 },
  { key: 'rebar-bay', type: 'rebar_bay', modelUrl: `${ROOT}rebar-bay-v3-medium.glb${ASSET_VERSION}`, lod: 'medium', minimumPixelSize: 18 },
  { key: 'wagon', type: 'wagon', modelUrl: `${ROOT}rebar-wagon-v3-medium.glb${ASSET_VERSION}`, lod: 'medium', minimumPixelSize: 20 },
  { key: 'ash-environment', type: 'ash_environment', modelUrl: `${ROOT}ash-core-v3-medium.glb${ASSET_VERSION}`, lod: 'medium' },
]

export function getModel(key: string): ModelDescriptor {
  const descriptor = MODEL_MANIFEST.find((entry) => entry.key === key)
  if (!descriptor) throw new Error(`Unknown 3D model key: ${key}`)
  return descriptor
}
