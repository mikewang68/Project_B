/** Data-driven CR-02 unloading sequence for the confirmed steel two-track line. */

export interface AnimStep {
  el: 'gantry' | 'trolley' | 'hoist'
  type: 'move' | 'trolley' | 'hoist'
  from: [number, number, number]
  to: [number, number, number]
  time: number
}

/**
 * Gantry moves along CAD X.  Trolley offset moves across the portal along CAD
 * Y, from the railway flatcar side to REBAR-BAY-07.  Values are simulated.
 */
export const CRANE_UNLOAD_SEQUENCE: AnimStep[] = [
  { el: 'gantry', type: 'move', from: [3727.235, 1673.434, 0], to: [3727.235, 1673.434, 0], time: 1200 },
  { el: 'trolley', type: 'trolley', from: [0, 0, 0], to: [-8.6, 0, 0], time: 2000 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 16], to: [0, 0, 5], time: 2000 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 5], to: [0, 0, 16], time: 2000 },
  { el: 'trolley', type: 'trolley', from: [-8.6, 0, 0], to: [36.585, 0, 0], time: 3000 },
  { el: 'gantry', type: 'move', from: [3727.235, 1673.434, 0], to: [3741.1065, 1673.434, 0], time: 1600 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 16], to: [0, 0, 5], time: 2000 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 5], to: [0, 0, 16], time: 1500 },
  { el: 'gantry', type: 'move', from: [3741.1065, 1673.434, 0], to: [3727.235, 1673.434, 0], time: 1600 },
  { el: 'trolley', type: 'trolley', from: [36.585, 0, 0], to: [0, 0, 0], time: 1800 },
]

export interface ActorState {
  gantry: [number, number]
  /** Trolley offset along CAD Y. */
  trolleyOffset: number
  hoistHeight: number
}

export function getStepDelta(deltaMs: number, step: AnimStep): [number, number, number] {
  if (step.time <= 0) return [0, 0, 0]
  const ratio = deltaMs / step.time
  return [
    (step.to[0] - step.from[0]) * ratio,
    (step.to[1] - step.from[1]) * ratio,
    (step.to[2] - step.from[2]) * ratio,
  ]
}
