import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { apiRequest, postJson } from '@/api/http'
import { SafetyLiveClient, type LiveState } from '@/api/live'
import { demoSnapshot } from '@/mock/snapshot'
import type { SafetySnapshot } from '@/types/safety'

export const useSafetyStore = defineStore('safety', () => {
  const snapshot = ref<SafetySnapshot>(structuredClone(demoSnapshot))
  const connection = ref<LiveState>('connecting')
  const usingFallback = ref(false)
  const loading = ref(false)
  const error = ref<string>()
  let liveClient: SafetyLiveClient | undefined

  const activeAlarms = computed(() => snapshot.value.alarms.filter((alarm) => !['已关闭', '误报'].includes(alarm.status)))
  const urgentAlarms = computed(() => activeAlarms.value.filter((alarm) => alarm.level === '紧急'))
  const riskyDevices = computed(() => snapshot.value.devices.filter((device) => !['normal', 'offline'].includes(device.status)))
  const pendingAi = computed(() => snapshot.value.aiEvents.filter((event) => event.status === 'pending'))

  async function initialize(): Promise<void> {
    loading.value = true
    try {
      snapshot.value = await apiRequest<SafetySnapshot>('/state')
      usingFallback.value = false
    } catch {
      usingFallback.value = true
      connection.value = 'offline'
    } finally {
      loading.value = false
    }
    liveClient = new SafetyLiveClient(
      (next) => {
        snapshot.value = next
        usingFallback.value = false
      },
      (state) => (connection.value = state),
    )
    liveClient.connect()
  }

  async function mutate(path: string, body: unknown): Promise<void> {
    error.value = undefined
    try {
      snapshot.value = await postJson<SafetySnapshot>(path, body)
      usingFallback.value = false
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '操作失败'
      throw cause
    }
  }

  const triggerScenario = (id: string) => mutate(`/demo/scenarios/${id}`, {})
  const setFenceEnabled = (id: string, enabled: boolean) => mutate(`/fences/${id}/state`, { enabled })
  const setRuleEnabled = (id: string, enabled: boolean) => mutate(`/rules/${id}/state`, { enabled })
  const reviewAi = (id: string, result: string) => mutate(`/ai-events/${id}/review`, { result, reviewer: 'AI复核员' })
  const actOnAlarm = (id: string, action: string) => mutate(`/alarms/${id}/actions`, { action, operator: '演示安全员', remark: '界面操作' })
  const setNetwork = (connected: boolean) => mutate('/simulation/network', { connected })
  const setPaused = (paused: boolean) => mutate('/simulation/pause', { paused })
  const setSpeed = (multiplier: number) => mutate('/simulation/speed', { multiplier })

  function dispose(): void {
    liveClient?.stop()
    liveClient = undefined
  }

  return { snapshot, connection, usingFallback, loading, error, activeAlarms, urgentAlarms, riskyDevices, pendingAi, initialize, triggerScenario, setFenceEnabled, setRuleEnabled, reviewAi, actOnAlarm, setNetwork, setPaused, setSpeed, dispose }
})
