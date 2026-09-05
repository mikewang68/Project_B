/**
 * useAnimation composable：驱动动作序列（复刻项目1 requestAnimationFrame + delta 插值）
 * - 按序播放 AnimStep[]，每帧用 deltaMs 计算位移，更新 ActorState
 * - 序列播完自动循环
 * - 通过 onUpdate 回调把动画状态暴露给外部（驱动 Cesium 实体）
 */
import { onUnmounted, ref } from 'vue'
import { getStepDelta, type AnimStep, type ActorState } from './animation'

export interface UseAnimationOptions {
  /** 初始 actor 状态 */
  initialState?: Partial<ActorState>
  /** 每帧更新回调（deltaMs 毫秒） */
  onUpdate?: (state: ActorState) => void
  /** 是否循环播放，默认 true */
  loop?: boolean
}

export function useAnimation(steps: AnimStep[], options: UseAnimationOptions = {}) {
  const actorState = ref<ActorState>({
    gantry: options.initialState?.gantry ?? [3800, 1700],
    trolleyOffset: options.initialState?.trolleyOffset ?? 0,
    hoistHeight: options.initialState?.hoistHeight ?? 25,
  })

  let stepIndex = 0
  let stepElapsed = 0
  let timerId: ReturnType<typeof setInterval> | null = null
  let lastTime: number | null = null
  const running = ref(false)
  // 帧间隔（ms）；headless 下 rAF 会被暂停，改用 setInterval 保证可靠
  const FRAME_MS = 16

  /** 应用一步：根据动作类型更新 actor */
  function applyStep(deltaMs: number) {
    const step = steps[stepIndex]
    if (!step) return
    const [dx, dy, dz] = getStepDelta(deltaMs, step)
    const s = actorState.value

    switch (step.el) {
      case 'gantry':
        s.gantry = [s.gantry[0] + dx, s.gantry[1] + dy]
        break
      case 'trolley':
        s.trolleyOffset += dx
        break
      case 'hoist':
        s.hoistHeight += dz
        break
    }
  }

  /** 推进到下一步，或循环 */
  function advance() {
    stepElapsed = 0
    stepIndex++
    if (stepIndex >= steps.length) {
      if (options.loop ?? true) {
        stepIndex = 0
      } else {
        stop()
        return
      }
    }
  }

  function tick() {
    if (!running.value) return
    const now = performance.now()
    if (lastTime === null) lastTime = now
    const deltaMs = Math.min(now - lastTime, 100) // 防止跳帧过大
    lastTime = now

    stepElapsed += deltaMs
    applyStep(deltaMs)

    // 步进时间已到 → 进入下一步
    const step = steps[stepIndex]
    if (step && stepElapsed >= step.time) {
      advance()
    }

    options.onUpdate?.(actorState.value)
  }

  function start() {
    if (running.value) return
    running.value = true
    lastTime = null
    timerId = setInterval(tick, FRAME_MS)
  }

  function stop() {
    running.value = false
    if (timerId !== null) {
      clearInterval(timerId)
      timerId = null
    }
  }

  /** 重置到初始状态 */
  function reset() {
    stop()
    stepIndex = 0
    stepElapsed = 0
    actorState.value = {
      gantry: options.initialState?.gantry ?? [3800, 1700],
      trolleyOffset: options.initialState?.trolleyOffset ?? 0,
      hoistHeight: options.initialState?.hoistHeight ?? 25,
    }
  }

  onUnmounted(stop)

  return { actorState, running, start, stop, reset }
}
