import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { apiRequest, postJson } from '@/api/http'
import { SafetyLiveClient, type LiveState } from '@/api/live'
import type { SafetySnapshot } from '@/types/safety'

function emptySnapshot():SafetySnapshot{return {
  schemaVersion:'1.0.0',updatedAt:'',dataQuality:'unavailable',
  workPlan:{id:'',name:'',shift:'',team:'',area:'',progress:0,status:''},
  people:[],fences:[],devices:[],aiEvents:[],alarms:[],rules:[],scenarios:[],auditLogs:[],
  system:{cloudConnected:false,edgeVersion:'',cachedEvents:0,availability:0,services:[]},
}}
export const useSafetyStore = defineStore('safety', () => {
  const snapshot = ref<SafetySnapshot>(emptySnapshot())
  const connection = ref<LiveState>('connecting')
  const usingFallback = ref(true)
  const loading = ref(false)
  const error = ref<string>()
  let liveClient: SafetyLiveClient | undefined
  let generation=0

  const activeAlarms = computed(() => snapshot.value.alarms.filter((alarm) => !['已关闭', '误报'].includes(alarm.status)))
  const urgentAlarms = computed(() => activeAlarms.value.filter((alarm) => alarm.level === '紧急'))
  const riskyDevices = computed(() => snapshot.value.devices.filter((device) => !['normal', 'offline'].includes(device.status)))
  const pendingAi = computed(() => snapshot.value.aiEvents.filter((event) => event.status === 'pending'))

  async function initialize(): Promise<void> {
    const currentGeneration=++generation
    liveClient?.stop()
    error.value=undefined
    loading.value = true
    try {
      const next=await apiRequest<SafetySnapshot>('/state')
      if(currentGeneration!==generation)return
      snapshot.value=next
      usingFallback.value = false
    } catch(cause) {
      if(currentGeneration!==generation)return
      snapshot.value=emptySnapshot()
      error.value=cause instanceof Error?cause.message:'Unable to load authorized data'
      usingFallback.value = true
      connection.value = 'offline'
    } finally {
      if(currentGeneration===generation)loading.value = false
    }
    if(currentGeneration!==generation)return
    liveClient = new SafetyLiveClient(
      (next) => {
        if(currentGeneration!==generation)return
        snapshot.value = next
        usingFallback.value = false
      },
      (state) => (connection.value = state),
    )
    liveClient.connect()
  }

  async function mutate(path: string, body: unknown): Promise<void> {
    const currentGeneration=generation
    error.value = undefined
    try {
      const next=await postJson<SafetySnapshot>(path, body)
      // 登出/授权版本变更（dispose）后到达的写响应不得覆盖已清空的状态
      if(currentGeneration!==generation)return
      snapshot.value=next
      usingFallback.value = false
    } catch (cause) {
      if(currentGeneration===generation){
        error.value = cause instanceof Error ? cause.message : '操作失败'
      }
      throw cause
    }
  }

  const triggerScenario = (id: string) => mutate(`/demo/scenarios/${id}`, {})
  const setFenceEnabled = (id: string, enabled: boolean) => mutate(`/fences/${id}/state`, { enabled })
  const setRuleEnabled = (id: string, enabled: boolean) => mutate(`/rules/${id}/state`, { enabled })
  // 操作人不写死：后端以会话主体作为审计兜底（本 store 仅服务于未挂路由的演示页）
  const reviewAi = (id: string, result: string) => mutate(`/ai-events/${id}/review`, { result })
  const actOnAlarm = (id: string, action: string) => mutate(`/alarms/${id}/actions`, { action, remark: '界面操作' })
  const setNetwork = (connected: boolean) => mutate('/simulation/network', { connected })
  const setPaused = (paused: boolean) => mutate('/simulation/pause', { paused })
  const setSpeed = (multiplier: number) => mutate('/simulation/speed', { multiplier })

  function dispose(): void {
    generation++;loading.value=false
    liveClient?.stop()
    liveClient = undefined
    snapshot.value=emptySnapshot();usingFallback.value=true;error.value=undefined
  }

  return { snapshot, connection, usingFallback, loading, error, activeAlarms, urgentAlarms, riskyDevices, pendingAi, initialize, triggerScenario, setFenceEnabled, setRuleEnabled, reviewAi, actOnAlarm, setNetwork, setPaused, setSpeed, dispose }
})
