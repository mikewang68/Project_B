/** Data-driven CR-02 unloading sequence for the V4 steel two-track line (货3/货4 -> transfer apron). */
import { SITE_LAYOUT_V4 } from './siteLayoutV4'

export interface AnimStep {
  el: 'gantry' | 'trolley' | 'hoist'
  type: 'move' | 'trolley' | 'hoist'
  from: [number, number, number]
  to: [number, number, number]
  time: number
}

const CR02_X = SITE_LAYOUT_V4.rebar.gantries[1].cadX
const CR02_Y = SITE_LAYOUT_V4.rebar.gantries[1].cadY
const BAY07_X = SITE_LAYOUT_V4.rebar.bays[6].cadX
const TRACK_H4_OFFSET = SITE_LAYOUT_V4.rebar.tracks[0].cadY - CR02_Y
const APRON_OFFSET = SITE_LAYOUT_V4.rebar.transferApronY[1] - CR02_Y - 1.5

export const CRANE_UNLOAD_SEQUENCE: AnimStep[] = [
  { el: 'gantry', type: 'move', from: [CR02_X, CR02_Y, 0], to: [CR02_X, CR02_Y, 0], time: 1200 },
  { el: 'trolley', type: 'trolley', from: [0, 0, 0], to: [TRACK_H4_OFFSET, 0, 0], time: 1800 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 16], to: [0, 0, 4.5], time: 1800 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 4.5], to: [0, 0, 16], time: 1800 },
  { el: 'trolley', type: 'trolley', from: [TRACK_H4_OFFSET, 0, 0], to: [APRON_OFFSET, 0, 0], time: 2600 },
  { el: 'gantry', type: 'move', from: [CR02_X, CR02_Y, 0], to: [BAY07_X, CR02_Y, 0], time: 1600 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 16], to: [0, 0, 4.8], time: 1800 },
  { el: 'hoist', type: 'hoist', from: [0, 0, 4.8], to: [0, 0, 16], time: 1500 },
  { el: 'gantry', type: 'move', from: [BAY07_X, CR02_Y, 0], to: [CR02_X, CR02_Y, 0], time: 1600 },
  { el: 'trolley', type: 'trolley', from: [APRON_OFFSET, 0, 0], to: [0, 0, 0], time: 1800 },
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
