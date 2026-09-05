import type { VehicleRoute } from './vehicleRoutes'

export interface VehiclePose {
  cadX: number
  cadY: number
  headingDeg: number
}

interface RouteRuntime {
  route: VehicleRoute
  segmentIndex: number
  distanceOnSegment: number
  dwellRemainingMs: number
}

function distance(a: [number, number], b: [number, number]) {
  return Math.hypot(b[0] - a[0], b[1] - a[1])
}

function poseFor(a: [number, number], b: [number, number], progress: number): VehiclePose {
  const dx = b[0] - a[0]
  const dy = b[1] - a[1]
  return {
    cadX: a[0] + dx * progress,
    cadY: a[1] + dy * progress,
    headingDeg: (Math.atan2(dy, dx) * 180) / Math.PI,
  }
}

/** Runs the explicitly marked simulation routes without changing CAD source geometry. */
export class VehicleRouteAnimator {
  private readonly runtimes: RouteRuntime[]
  private readonly onPose: (vehicleId: string, pose: VehiclePose) => void
  private readonly onFrame: () => void
  private timer: ReturnType<typeof setInterval> | null = null
  private lastTime = 0

  constructor(
    routes: VehicleRoute[],
    onPose: (vehicleId: string, pose: VehiclePose) => void,
    onFrame: () => void,
  ) {
    this.onPose = onPose
    this.onFrame = onFrame
    this.runtimes = routes.map((route) => ({ route, segmentIndex: 0, distanceOnSegment: 0, dwellRemainingMs: 0 }))
    for (const runtime of this.runtimes) this.emitPose(runtime)
  }

  start() {
    if (this.timer) return
    this.lastTime = performance.now()
    this.timer = setInterval(() => this.tick(), 50)
  }

  stop() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  private tick() {
    const now = performance.now()
    const deltaMs = Math.min(now - this.lastTime, 180)
    this.lastTime = now
    for (const runtime of this.runtimes) this.advance(runtime, deltaMs)
    this.onFrame()
  }

  private advance(runtime: RouteRuntime, initialDeltaMs: number) {
    let deltaMs = initialDeltaMs
    const { route } = runtime
    if (route.points.length < 2) return

    while (deltaMs > 0) {
      if (runtime.dwellRemainingMs > 0) {
        const consumed = Math.min(deltaMs, runtime.dwellRemainingMs)
        runtime.dwellRemainingMs -= consumed
        deltaMs -= consumed
        if (runtime.dwellRemainingMs > 0) break
      }

      const from = route.points[runtime.segmentIndex]
      const to = route.points[(runtime.segmentIndex + 1) % route.points.length]
      const length = distance(from, to)
      if (length < 0.01) {
        runtime.segmentIndex = (runtime.segmentIndex + 1) % route.points.length
        continue
      }
      const remaining = length - runtime.distanceOnSegment
      const travel = (route.speedMps * deltaMs) / 1000
      if (travel < remaining) {
        runtime.distanceOnSegment += travel
        this.emitPose(runtime)
        break
      }

      const consumedMs = (remaining / route.speedMps) * 1000
      deltaMs -= consumedMs
      runtime.segmentIndex = (runtime.segmentIndex + 1) % route.points.length
      runtime.distanceOnSegment = 0
      runtime.dwellRemainingMs = route.dwellMs
      this.emitPose(runtime)
    }
  }

  private emitPose(runtime: RouteRuntime) {
    const { route } = runtime
    const from = route.points[runtime.segmentIndex]
    const to = route.points[(runtime.segmentIndex + 1) % route.points.length]
    const length = distance(from, to)
    this.onPose(route.vehicleId, poseFor(from, to, length ? runtime.distanceOnSegment / length : 0))
  }
}
