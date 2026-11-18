import * as Cesium from 'cesium'
import { cadToCartesian3 } from './geo'
import { SITE_LAYOUT_V4 } from './siteLayoutV4'

export type CameraPresetId =
  | 'overview'
  | 'gantry'
  | 'rebar'
  | 'route'
  | 'processing'
  | 'steel'
  | 'ash'
  | 'tipper'
  | 'loader'
  | 'weighbridge'

interface CameraPreset {
  cadX: number
  cadY: number
  cadZ?: number
  radius: number
  headingDeg: number
  pitchDeg: number
  range: number
}

export interface CameraFocusTarget {
  cadX: number
  cadY: number
  cadZ?: number
  radius?: number
  headingDeg?: number
  pitchDeg?: number
  range?: number
  durationSec?: number
  tag?: string
}

const CAMERA_PRESETS: Record<CameraPresetId, CameraPreset> = {
  // Whole-station overview calibrated from 给大连大.dwg (local X:3310..4584, Y:1487..1873)
  overview: {
    cadX: SITE_LAYOUT_V4.siteShellCenter.cadX,
    cadY: 1625,
    radius: 680,
    headingDeg: -22,
    pitchDeg: -52,
    range: 980,
  },
  // Steel transfer interface: 40t gantry crane (CR-02) handing off into indoor rebar warehouse bay 07
  gantry: {
    cadX: SITE_LAYOUT_V4.rebar.gantries[1].cadX,
    cadY: 1662,
    radius: 38,
    headingDeg: -42,
    pitchDeg: -26,
    range: 195,
  },
  // Indoor bridge crane bay inspection (20t magnetic / 32t C-hook, 13 bays)
  rebar: {
    cadX: SITE_LAYOUT_V4.rebar.bays[6].cadX,
    cadY: SITE_LAYOUT_V4.rebar.bays[6].cadY,
    radius: 34,
    headingDeg: 18,
    pitchDeg: -38,
    range: 145,
  },
  route: {
    cadX: 3920,
    cadY: 1605,
    radius: 260,
    headingDeg: -16,
    pitchDeg: -56,
    range: 540,
  },
  processing: {
    cadX: 3700,
    cadY: 1826,
    radius: 85,
    headingDeg: 16,
    pitchDeg: -46,
    range: 260,
  },
  steel: {
    cadX: 3751,
    cadY: 1682,
    radius: 135,
    headingDeg: -28,
    pitchDeg: -48,
    range: 360,
  },
  // Bulk cement/fly-ash line: 40 x 3000T silos + 4 unloading complexes + 3 bulk gantries
  ash: {
    cadX: 3893,
    cadY: 1545,
    radius: 170,
    headingDeg: -12,
    pitchDeg: -46,
    range: 420,
  },
  // Close-up: Bulk gantry -> Mobile tipper FX-01 -> Semi-enclosed unloading room (42m3 hopper) -> Bucket elevator
  tipper: {
    cadX: SITE_LAYOUT_V4.ash.unloadingComplexes[0].cadX + 18,
    cadY: 1528,
    radius: 36,
    headingDeg: -38,
    pitchDeg: -28,
    range: 155,
  },
  // Close-up: Under-silo quantitative tanker truck loading & telescopic spout (viewed from WNW open apron into Row-A drive-through tunnel)
  loader: {
    cadX: SITE_LAYOUT_V4.ash.silos[0].cadX - 1,
    cadY: SITE_LAYOUT_V4.ash.silos[0].cadY,
    cadZ: 4.0,
    radius: 18,
    headingDeg: 110,
    pitchDeg: -12,
    range: 64,
  },
  // East gate: 2 x 100t truck weighbridges, freight office, electromechanical warehouse
  weighbridge: {
    cadX: 4545,
    cadY: 1608,
    cadZ: 2.0,
    radius: 36,
    headingDeg: -38,
    pitchDeg: -32,
    range: 155,
  },
}

export function flyToCameraPreset(viewer: Cesium.Viewer, id: CameraPresetId, duration = 0): void {
  const preset = CAMERA_PRESETS[id]
  viewer.camera.cancelFlight()
  viewer.camera.flyToBoundingSphere(
    new Cesium.BoundingSphere(cadToCartesian3(preset.cadX, preset.cadY, preset.cadZ ?? 0), preset.radius),
    {
      duration,
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(preset.headingDeg),
        Cesium.Math.toRadians(preset.pitchDeg),
        preset.range,
      ),
    },
  )
  viewer.scene.requestRender()
}

export function focusCadPoint(viewer: Cesium.Viewer, target: CameraFocusTarget): void {
  viewer.camera.cancelFlight()
  viewer.camera.flyToBoundingSphere(
    new Cesium.BoundingSphere(cadToCartesian3(target.cadX, target.cadY, target.cadZ ?? 4), target.radius ?? 22),
    {
      duration: target.durationSec ?? 0.9,
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(target.headingDeg ?? -34),
        Cesium.Math.toRadians(target.pitchDeg ?? -24),
        target.range ?? 118,
      ),
    },
  )
  viewer.scene.requestRender()
}
