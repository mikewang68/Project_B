<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, Warning, Position } from '@element-plus/icons-vue'
import SafetyMapCanvas from '@/components/shared/SafetyMapCanvas.vue'
import PersonnelList from '@/components/personnel/PersonnelList.vue'
import PersonnelDetailDrawer from '@/components/personnel/PersonnelDetailDrawer.vue'
import TrackPlayback from '@/components/personnel/TrackPlayback.vue'
import type { PersonnelRecord, TrackPoint } from '@/types/personnel'
import type { PersonnelPoint } from '@/types/overview'
import type { FenceRecord } from '@/types/fence'
import { personnelApi } from '@/api/personnel'
import { fenceApi } from '@/api/fences'
import { isDomainLiveEvent, sharedLiveSocket } from '@/api/live'

const query = ref(''), team = ref(''), area = ref(''), state = ref(''), bracelet = ref(''), onlyAbnormal = ref(false)
const lowBatteryDemo = ref(false), selectedId = ref('P-ZHAO'), drawerOpen = ref(false)
const trackOpen = ref(false), playing = ref(false), trackIndex = ref(0), speed = ref(1)
const people = ref<PersonnelRecord[]>([])
const fences = ref<FenceRecord[]>([])
const track = ref<TrackPoint[]>([])
const backendStats = ref({ online: 0, abnormal: 0, bandOffline: 0, lowBattery: 0 })
let timer: ReturnType<typeof setInterval> | undefined
let reloadTimer: ReturnType<typeof setTimeout> | undefined
let disposeLive: (() => void) | undefined

const filteredPeople = computed(() => people.value)
const selectedPerson = computed(() => people.value.find((person) => person.id === selectedId.value))
const mapPeople = computed<PersonnelPoint[]>(() => people.value.map((person) => person.id === selectedId.value && trackOpen.value
  ? { ...person, x: track.value[trackIndex.value]?.x ?? person.x, y: track.value[trackIndex.value]?.y ?? person.y }
  : person))
const stats = computed(() => [
  { label: '在线人员', value: backendStats.value.online, tone: 'primary' },
  { label: '异常人员', value: backendStats.value.abnormal, tone: 'warning' },
  { label: '手环离线', value: backendStats.value.bandOffline, tone: 'muted' },
  { label: '低电量', value: backendStats.value.lowBattery, tone: 'warning' },
])

async function loadList(): Promise<void> {
  try {
    const res = await personnelApi.list({
      keyword: query.value, team: team.value, area: area.value, state: state.value,
      bracelet: bracelet.value, onlyAbnormal: onlyAbnormal.value || undefined,
    })
    people.value = res.list
    backendStats.value = res.stats
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '人员数据加载失败')
  }
}

async function loadFences(): Promise<void> {
  try {
    fences.value = (await fenceApi.list()).filter((f) => f.status !== '已停用')
  } catch {
    fences.value = []
  }
}

/** 筛选变化后请求后端（轻量防抖，避免输入时逐字请求） */
function scheduleReload(): void {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(loadList, 220)
}
;[query, team, area, state, bracelet, onlyAbnormal].forEach((r) => watch(r, scheduleReload))

function selectPerson(person: PersonnelPoint): void { selectedId.value = person.id; drawerOpen.value = true }
function setPlaying(value: boolean): void { playing.value = value }
async function startPlayback(): Promise<void> {
  drawerOpen.value = false
  try {
    track.value = await personnelApi.track(selectedId.value)
  } catch {
    track.value = []
  }
  trackOpen.value = true
  trackIndex.value = 0
  playing.value = true
}
function exitPlayback(): void { trackOpen.value = false; playing.value = false; trackIndex.value = 0 }

async function toggleDemo(): Promise<void> {
  const next = !lowBatteryDemo.value
  try {
    await personnelApi.simulateAbnormal('P-ZHAO', next ? 'lowBattery' : 'restore')
    lowBatteryDemo.value = next
    ElMessage[next ? 'warning' : 'success'](next ? '赵磊手环电量低于安全阈值' : '赵磊手环状态已恢复正常')
    await loadList()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '模拟失败')
  }
}

/** 模拟赵磊进入已生效危险围栏：后端 PIP 命中后经 AlertService 建单并广播五端 */
async function simulateIntrusion(): Promise<void> {
  try {
    await personnelApi.simulateAbnormal('P-ZHAO', 'intrusion')
    ElMessage.warning('赵磊进入龙门吊动态禁区，已生成人员越界告警')
    await loadList()
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '越界模拟失败')
  }
}

async function remind(): Promise<void> {
  if (!selectedId.value) return
  try {
    const res = await personnelApi.remind(selectedId.value)
    ElMessage.success(res.message)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '提醒发送失败')
  }
}

async function showAlerts(): Promise<void> {
  if (!selectedId.value) return
  try {
    const list = await personnelApi.alerts(selectedId.value)
    if (!list.length) ElMessage.info('该人员当前没有相关告警')
    else ElMessage.info(`${selectedPerson.value?.name ?? ''} 相关告警 ${list.length} 条，可在告警中心查看处理`)
  } catch {
    ElMessage.info('告警数据暂不可用')
  }
}

function tick(): void { if (trackIndex.value >= track.value.length - 1) playing.value = false; else trackIndex.value += 1 }
watch([playing, speed], () => { if (timer) clearInterval(timer); timer = playing.value ? setInterval(tick, 850 / speed.value) : undefined }, { immediate: true })

onMounted(() => {
  void loadList()
  void loadFences()
  sharedLiveSocket.connect()
  disposeLive = sharedLiveSocket.onMessage((raw) => {
    if (!isDomainLiveEvent(raw)) return
    if (String(raw.type).startsWith('person.')) void loadList()
    if (String(raw.type) === 'fence.changed') void loadFences()
  })
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  if (reloadTimer) clearTimeout(reloadTimer)
  disposeLive?.()
})
</script>

<template>
  <section class="page-content personnel-page">
    <header class="module-intro header-actions-only">
      <div class="module-stats">
        <div v-for="item in stats" :key="item.label" :data-tone="item.tone"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></div>
        <button type="button" class="demo-outline-button" :class="{ active: lowBatteryDemo }" @click="toggleDemo"><el-icon><Warning /></el-icon>{{ lowBatteryDemo ? '恢复人员正常' : '模拟低电量' }}</button>
        <button type="button" class="demo-outline-button" @click="simulateIntrusion"><el-icon><Position /></el-icon>模拟越界</button>
      </div>
    </header>

    <div class="compact-filter-bar dashboard-card">
      <el-input v-model="query" clearable placeholder="搜索人员姓名/工号"><template #prefix><el-icon><Search /></el-icon></template></el-input>
      <el-select v-model="team" clearable placeholder="班组"><el-option v-for="item in ['装卸一班','装卸二班','安全管理','设备保障','外协单位']" :key="item" :label="item" :value="item" /></el-select>
      <el-select v-model="area" clearable placeholder="当前区域"><el-option v-for="item in ['装卸区 A','装卸区 B','龙门吊作业区','翻箱机作业区','车辆通道']" :key="item" :label="item" :value="item" /></el-select>
      <el-select v-model="state" clearable placeholder="人员状态"><el-option label="正常" value="正常" /><el-option label="异常" value="异常" /></el-select>
      <el-select v-model="bracelet" clearable placeholder="手环状态"><el-option label="在线" value="在线" /><el-option label="低电量" value="低电量" /><el-option label="离线" value="离线" /></el-select>
      <label>仅看异常<el-switch v-model="onlyAbnormal" /></label>
    </div>

    <div class="personnel-workspace">
      <article class="dashboard-card personnel-map-card">
        <header class="workspace-card-header"><div><span>LIVE POSITION</span><h2>站场人员分布</h2><p>复用安全态势站场图 · 位置来自后端 /personnel/live</p></div><div class="map-legend"><span><i></i>正常</span><span><i></i>异常</span><span><i></i>危险</span></div></header>
        <div class="personnel-map-stage"><SafetyMapCanvas :people="mapPeople" :fences="fences" :selected-person-id="selectedId" :track="trackOpen ? track : []" :track-index="trackIndex" :show-equipment="false" @select-person="selectPerson" /></div>
        <TrackPlayback v-if="trackOpen" :playing="playing" :index="trackIndex" :total="track.length" :speed="speed" :time="track[trackIndex]?.time ?? ''" @play="setPlaying(true)" @pause="setPlaying(false)" @seek="trackIndex = $event" @speed="speed = $event" @exit="exitPlayback" />
      </article>
      <PersonnelList :people="filteredPeople" :selected-id="selectedId" @select="selectPerson" />
    </div>

    <PersonnelDetailDrawer v-model="drawerOpen" :person="selectedPerson" @locate="drawerOpen = false" @playback="startPlayback" @remind="remind" @alerts="showAlerts" />
  </section>
</template>
