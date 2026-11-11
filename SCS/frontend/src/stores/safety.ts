import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { apiRequest } from '@/api/http'
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

  function dispose(): void {
    generation++;loading.value=false
    liveClient?.stop()
    liveClient = undefined
    snapshot.value=emptySnapshot();usingFallback.value=true;error.value=undefined
  }

  return { snapshot, connection, usingFallback, loading, error, activeAlarms, urgentAlarms, riskyDevices, pendingAi, initialize, dispose }
})
