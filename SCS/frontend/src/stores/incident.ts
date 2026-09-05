import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { AlertRisk } from '@/types/alert'
import type { IncidentPhoto, MobileIncident } from '@/types/incident'
import { alertApi, type TreatmentPayload } from '@/api/alerts'
import { mobileApi, type MobileHome } from '@/api/mobile'
import { screenApi, type ScreenOverview } from '@/api/screen'
import { mapAlertPage, mapAlertEvent } from '@/adapters/alert'
import { mapAlertToMobileIncident } from '@/adapters/mobileAlert'

function hms(d = new Date()): string {
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

/**
 * 大屏 + 移动端共享事件 store。
 * 第四阶段起：Alert 后端是唯一权威事件源，本 store 只做加载/投影与写操作转发；
 * 跨页面联动经 Backend WebSocket 触发 reload，不再使用 BroadcastChannel 同步业务状态。
 * 地图坐标、距离、照片仍为 Mock（见 MobileAlertAdapter / MobileEvidenceUpload）。
 */
export const useIncidentStore = defineStore('incident', () => {
  const incidents = ref<MobileIncident[]>([])
  const overview = ref<ScreenOverview | null>(null)
  const home = ref<MobileHome | null>(null)
  const loading = ref(false)
  const error = ref('')
  const loaded = ref(false)
  /** 大屏已读的紧急事件 id（确认已读后不再弹窗；新升级事件自动弹出） */
  const acknowledged = ref<Set<string>>(new Set())
  const syncAt = ref('')

  // ---- 统计 ----
  const pendingCount = computed(() => incidents.value.filter((i) => i.status === '待接单').length)
  const handlingCount = computed(() =>
    incidents.value.filter((i) => ['已接单', '已到场', '处理中', '待复核'].includes(i.status)).length)
  const criticalCount = computed(() => incidents.value.filter((i) => i.risk === '紧急' && i.status !== '已关闭').length)
  const closedToday = computed(() => home.value?.closedToday ?? incidents.value.filter((i) => i.status === '已关闭').length)

  /** 当前需要大屏弹窗的紧急未关闭事件（未确认已读、按时间最新） */
  const criticalIncident = computed(() => {
    const candidates = incidents.value
      .filter((i) => i.risk === '紧急' && i.status !== '已关闭' && !acknowledged.value.has(i.id))
      .sort((a, b) => (b.occurredAt ?? b.time).localeCompare(a.occurredAt ?? a.time))
    return candidates[0] ?? null
  })
  /** 存在未读紧急事件时，地图进入高风险演示态 */
  const breachActive = computed(() => criticalIncident.value !== null)

  function byId(id: string): MobileIncident | undefined {
    return incidents.value.find((i) => i.id === id)
  }

  function upsert(incident: MobileIncident): void {
    const idx = incidents.value.findIndex((i) => i.id === incident.id)
    if (idx >= 0) incidents.value.splice(idx, 1, incident)
    else incidents.value.unshift(incident)
  }

  // ---- 加载（REST 权威读取） ----
  async function loadAll(): Promise<void> {
    loading.value = true
    error.value = ''
    try {
      const [pageResult, overviewResult, homeResult] = await Promise.all([
        alertApi.getAlerts({ page: 1, pageSize: 200 }),
        screenApi.getOverview(),
        mobileApi.getHome(),
      ])
      const page = mapAlertPage(pageResult)
      incidents.value = page.list.map(mapAlertToMobileIncident)
      overview.value = overviewResult
      home.value = homeResult
      loaded.value = true
      syncAt.value = hms()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '事件加载失败'
    } finally {
      loading.value = false
    }
  }

  /** 拉取单条权威详情并更新本地投影（打开详情 / 写操作后调用） */
  async function loadDetail(id: string): Promise<MobileIncident | undefined> {
    const dto = await alertApi.getAlertDetail(id)
    const incident = mapAlertToMobileIncident(mapAlertEvent(dto))
    upsert(incident)
    return incident
  }

  let refreshTimer: number | undefined
  /** WS 事件触发的去抖刷新（避免一次状态变更触发多次请求） */
  function scheduleRefresh(): void {
    if (refreshTimer) window.clearTimeout(refreshTimer)
    refreshTimer = window.setTimeout(() => { void loadAll() }, 280)
  }

  // ---- 大屏紧急事件 ----
  function acknowledgeCritical(): void {
    if (criticalIncident.value) acknowledged.value.add(criticalIncident.value.id)
    // 触发响应式更新
    acknowledged.value = new Set(acknowledged.value)
  }

  /** 演示按钮：重新定位当前紧急事件（清除其已读标记并重新拉取，不构造虚假事件） */
  async function spotlightCritical(): Promise<boolean> {
    await loadAll()
    const current = incidents.value.find((i) => i.risk === '紧急' && i.status !== '已关闭')
    if (!current) return false
    acknowledged.value = new Set([...acknowledged.value].filter((id) => id !== current.id))
    return true
  }

  // ---- 移动端处置（全部转发后端，成功后以 REST 详情为准） ----
  async function accept(id: string): Promise<void> {
    await mobileApi.accept(id)
    await loadDetail(id)
  }

  async function arrive(id: string): Promise<void> {
    await mobileApi.arrive(id)
    await loadDetail(id)
  }

  async function startHandle(id: string): Promise<void> {
    await alertApi.startAlert(id)
    await loadDetail(id)
  }

  async function submitTreatment(id: string, payload: {
    measures: string[]
    siteNote: string
    riskCleared: boolean
    note: string
    photos: IncidentPhoto[]
  }): Promise<void> {
    const body: TreatmentPayload = {
      measures: payload.measures,
      result: payload.riskCleared ? '风险已解除' : '仍需观察',
      attachment: payload.photos.length ? `现场照片 ${payload.photos.length} 张（Mock）` : undefined,
      note: [payload.siteNote, payload.note].filter(Boolean).join('；') || undefined,
      riskResolved: payload.riskCleared,
    }
    await alertApi.submitTreatment(id, body)
    await loadDetail(id)
  }

  async function reviewClose(id: string): Promise<void> {
    await alertApi.reviewAlert(id, { reviewer: '刘志明' })
    await loadDetail(id)
  }

  async function escalate(id: string, payload: { reason: string; level: AlertRisk; targets: string }): Promise<void> {
    await alertApi.escalateAlert(id, { level: payload.level, reason: `${payload.reason}（通知：${payload.targets}）` })
    // 升级后的紧急事件需要在大屏自动弹窗
    acknowledged.value = new Set([...acknowledged.value].filter((ackId) => ackId !== id))
    await loadDetail(id)
  }

  /** 请求紧急联动（移动端不直接控制设备，调用后端演示链路 success/fail） */
  async function requestLinkage(id: string, mode: 'success' | 'fail' = 'success'): Promise<void> {
    await alertApi.triggerLinkage(id, mode)
    await loadDetail(id)
  }

  // 现场照片仍为本地 Mock（无对象存储）
  function makePhoto(name: string, thumb = 'mock'): IncidentPhoto {
    return { id: `ph-${Date.now()}`, name, thumb, time: hms() }
  }

  return {
    incidents, overview, home, loading, error, loaded, syncAt,
    pendingCount, handlingCount, criticalCount, closedToday,
    criticalIncident, breachActive,
    byId, loadAll, loadDetail, scheduleRefresh,
    acknowledgeCritical, spotlightCritical,
    accept, arrive, startHandle, submitTreatment, reviewClose, escalate, requestLinkage,
    makePhoto,
  }
})
