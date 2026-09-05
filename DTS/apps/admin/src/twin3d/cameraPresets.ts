import * as Cesium from 'cesium'
import { cadToCartesian3 } from './geo'

export type CameraPresetId = 'overview' | 'gantry' | 'rebar' | 'route' | 'processing' | 'steel' | 'ash'

interface CameraPreset {
  cadX: number
  cadY: number
  radius: number
  headingDeg: number
  pitchDeg: number
  range: number
}

const CAMERA_PRESETS: Record<CameraPresetId, CameraPreset> = {
  // The overview is deliberately closer and steeper than the engineering CAD
  // view: assets read as volumes first, while the CAD remains a dim underlay.
  // Whole-station shell overview: the site-shell envelope spans roughly
  // X=3280..4680 and Y=1420..1900, so keep this camera wide enough to show
  // the corrected core lines and the surrounding functional-zone blockout.
  overview: { cadX: 3980, cadY: 1660, radius: 760, headingDeg: -28, pitchDeg: -58, range: 1080 },
  // Cesium's ellipsoid frame makes the apparent local height roughly one third
  // of HeadingPitchRange.range for this globe-free scene; keep close shots outside
  // the structure instead of putting the camera inside a GLB.
  gantry: { cadX: 3727.235, cadY: 1673.434, radius: 42, headingDeg: -48, pitchDeg: -28, range: 255 },
  // Keep the rebar-bay inspection crop inside the steel corridor.  The ash
  // line is over 180 m south of this target and must not enter this shot.
  rebar: { cadX: 3831.14, cadY: 1710.019, radius: 24, headingDeg: 18, pitchDeg: -58, range: 135 },
  route: { cadX: 3780, cadY: 1668, radius: 120, headingDeg: -14, pitchDeg: -62, range: 390 },
  processing: { cadX: 3688, cadY: 1821, radius: 95, headingDeg: 18, pitchDeg: -48, range: 310 },
  steel: { cadX: 3741.1065, cadY: 1710.019, radius: 105, headingDeg: -36, pitchDeg: -57, range: 290 },
  // Separate from the steel line: the two ash tracks are below the two silo
  // groups, so this preset intentionally centres on Y≈1530–1548.
  ash: { cadX: 3769, cadY: 1530, radius: 300, headingDeg: 0, pitchDeg: -75, range: 760 },
}

export function flyToCameraPreset(viewer: Cesium.Viewer, id: CameraPresetId, duration = 0): void {
  const preset = CAMERA_PRESETS[id]
  viewer.camera.cancelFlight()
  viewer.camera.flyToBoundingSphere(
    new Cesium.BoundingSphere(cadToCartesian3(preset.cadX, preset.cadY), preset.radius),
    {
      duration,
      offset: new Cesium.HeadingPitchRange(
        Cesium.Math.toRadians(preset.headingDeg),
        Cesium.Math.toRadians(preset.pitchDeg),
        preset.range,
      ),
    },
  )
  // The viewer deliberately uses requestRenderMode.  Zero-duration preset
  // changes are deterministic across Chrome/GPU combinations; the operational
  // animations remain continuous while camera navigation is always reliable.
  viewer.scene.requestRender()
}
