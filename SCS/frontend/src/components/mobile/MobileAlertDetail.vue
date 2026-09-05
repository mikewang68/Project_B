<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Location, Promotion } from '@element-plus/icons-vue'
import SafetyMapCanvas from '@/components/shared/SafetyMapCanvas.vue'
import AlertEvidence from '@/components/alerts/AlertEvidence.vue'
import AlertTimeline from '@/components/alerts/AlertTimeline.vue'
import MobileTreatmentForm from './MobileTreatmentForm.vue'
import MobileEscalateDialog from './MobileEscalateDialog.vue'
import { useIncidentStore } from '@/stores/incident'
import type { EquipmentPoint, PersonnelPoint } from '@/types/overview'
import type { FenceRecord } from '@/types/fence'
import type { AlertRisk } from '@/types/alert'

const route = useRoute()
const router = useRouter()
const store = useIncidentStore()
const detailId = String(route.params.id)
const acting = ref(false)

// 列表已有投影，打开详情时再拉一次权威详情（证据/时间线/联动以后端为准）
onMounted(() => {
  void store.loadDetail(detailId).catch((cause: unknown) => {
    ElMessage.error(cause instanceof Error ? cause.message : '详情加载失败')
  })
})

const incident = computed(() => store.byId(detailId))
const showForm = ref(false)
const escalateOpen = ref(false)

const miniFences: FenceRecord[] = [
  { id: 'F-MINI', name: '风险区域', kind: '危险区域', tone: 'danger', area: '', version: 'v3.3', status: '已生效', effectiveAt: '', expiresAt: '', teams: '', approver: '', edgeSynced: 4, edgeTotal: 4, polygon: [{ x: 38, y: 5 }, { x: 69, y: 5 }, { x: 69, y: 40 }, { x: 38, y: 40 }], nodes: [] },
]

const mapPeople = computed<PersonnelPoint[]>(() => {
  if (!incident.value) return []
  return [{
    id: 'target', name: incident.value.target.split('（')[0] ?? incident.value.target, team: incident.value.area,
    status: '在线', battery: 80, area: incident.value.area,
    risk: incident.value.risk === '一般' || incident.value.risk === '预警' ? '关注' : '高风险',
    state: incident.value.risk === '一般' || incident.value.risk === '预警' ? 'warning' : 'danger',
    x: incident.value.pos.x, y: incident.value.pos.y,
  }]
})
const mapEquipment = computed<EquipmentPoint[]>(() => {
  if (!incident.value || incident.value.evidence.kind !== 'collision') return []
  return [{ id: 'eq', name: '风险设备', type: 'vehicle', state: 'warning', x: Math.min(92, incident.value.pos.x + 8), y: incident.value.pos.y }]
})

async function runAction(work: () => Promise<void>, success: string): Promise<void> {
  acting.value = true
  try {
    await work()
    ElMessage.success(success)
  } catch (cause) {
    // 409 STATE_CONFLICT 等：直接展示后端 message，状态不变
    ElMessage.error(cause instanceof Error ? cause.message : '操作失败，请稍后重试')
  } finally {
    acting.value = false
  }
}

function accept(): void {
  if (!incident.value) return
  void runAction(() => store.accept(incident.value!.id), '已接单')
}
function arrive(): void {
  if (!incident.value) return
  void runAction(() => store.arrive(incident.value!.id), '已记录现场到达时间')
}
function startHandle(): void {
  if (!incident.value) return
  void runAction(async () => {
    await store.startHandle(incident.value!.id)
    showForm.value = true
  }, '已开始现场处置')
}
function requestLinkage(): void {
  if (!incident.value) return
  void runAction(() => store.requestLinkage(incident.value!.id, 'success'), '紧急联动请求已发送')
}
function onEscalate(payload: { reason: string; level: AlertRisk; targets: string }): void {
  if (!incident.value) return
  void runAction(() => store.escalate(incident.value!.id, payload), `已升级为${payload.level}，已通知${payload.targets}`)
}
function reviewClose(): void {
  if (!incident.value) return
  void runAction(() => store.reviewClose(incident.value!.id), '复核通过，事件已关闭')
}
async function onSubmitted(): Promise<void> {
  showForm.value = false
  if (incident.value) await store.loadDetail(incident.value.id).catch(() => undefined)
}
</script>

<template>
  <div v-if="incident" class="m-detail">
    <header class="m-detail__head">
      <button type="button" class="m-back" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div>
        <h2>{{ incident.title }}</h2>
        <p>
          <span class="m-risk-tag">{{ incident.risk }}</span>
          <span class="m-status-tag" :data-tone="incident.status">{{ incident.status }}</span>
          <span class="m-source">{{ incident.source }}</span>
        </p>
      </div>
    </header>

    <section class="m-info-card">
      <dl>
        <div><dt>事件编号</dt><dd class="mono">{{ incident.id }}</dd></div>
        <div><dt>发生时间</dt><dd>{{ incident.time }}</dd></div>
        <div><dt>区域</dt><dd>{{ incident.area }}</dd></div>
        <div><dt>对象</dt><dd>{{ incident.target }}</dd></div>
        <div><dt>责任人</dt><dd>{{ incident.assignee }}</dd></div>
        <div><dt>距当前位置</dt><dd>{{ incident.distanceM }}m（Demo 估算）</dd></div>
        <div v-if="incident.acceptTime"><dt>接单时间</dt><dd>{{ incident.acceptTime }}</dd></div>
        <div v-if="incident.arriveTime"><dt>到场时间</dt><dd>{{ incident.arriveTime }}</dd></div>
      </dl>
    </section>

    <section class="m-info-card">
      <h4><el-icon><Location /></el-icon>事件位置</h4>
      <div class="m-mini-map">
        <SafetyMapCanvas :people="mapPeople" :equipment="mapEquipment" :fences="miniFences" :show-equipment="true" />
      </div>
    </section>

    <section class="m-info-card">
      <h4>事件证据</h4>
      <AlertEvidence :evidence="incident.evidence" />
    </section>

    <section v-if="incident.risk === '紧急'" class="m-info-card m-linkage-card">
      <h4>紧急联动状态（只读）</h4>
      <ul>
        <li :data-state="incident.linkage?.soundLight === 'done' ? 'done' : incident.linkage?.soundLight === 'failed' ? 'fail' : 'wait'">现场声光提醒</li>
        <li :data-state="incident.linkage?.shutdown === 'done' ? 'done' : incident.linkage?.shutdown === 'failed' ? 'fail' : 'wait'">设备停机请求</li>
        <li :data-state="incident.linkage?.plc === 'done' ? 'done' : incident.linkage?.plc === 'running' ? 'run' : incident.linkage?.plc === 'failed' ? 'fail' : 'wait'">
          PLC 回执{{ incident.linkage?.plc === 'running' ? '（确认中）' : incident.linkage?.plc === 'failed' ? '（超时，需人工接管）' : '' }}
        </li>
      </ul>
      <button
        type="button"
        class="m-btn m-btn--danger m-btn--block"
        :disabled="acting || incident.linkage?.requested"
        @click="requestLinkage"
      >
        <el-icon><Promotion /></el-icon>{{ incident.linkage?.requested ? '联动请求已发送' : '请求紧急联动' }}
      </button>
      <p class="m-linkage-note">移动端不直接控制设备，仅发起联动请求，由边缘与管理端执行。</p>
    </section>

    <section class="m-info-card">
      <AlertTimeline :nodes="incident.timeline" />
    </section>

    <!-- 处置中：处置表单 -->
    <MobileTreatmentForm
      v-if="incident.status === '处理中' && showForm"
      :incident-id="incident.id"
      @submitted="onSubmitted"
    />

    <!-- 待复核：提交摘要 -->
    <section v-if="incident.status === '待复核'" class="m-info-card m-result-card">
      <h4>已提交处置结果</h4>
      <p><b>处置措施：</b>{{ incident.measures.length ? incident.measures.join('、') : '—' }}</p>
      <p><b>现场情况：</b>{{ incident.siteNote || '—' }}</p>
      <p><b>风险状态：</b>{{ incident.riskCleared ? '风险已解除' : '仍需观察' }}</p>
      <p v-if="incident.note"><b>备注：</b>{{ incident.note }}</p>
      <p v-if="incident.photos.length"><b>现场照片：</b>{{ incident.photos.length }} 张（Mock）</p>
      <button type="button" class="m-btn m-btn--primary m-btn--block" :disabled="acting" @click="reviewClose">复核通过并关闭</button>
    </section>

    <section v-if="incident.status === '已关闭'" class="m-info-card m-closed-card">
      事件已关闭，处置闭环完成
    </section>

    <!-- 底部固定操作栏 -->
    <footer class="m-action-bar" v-if="['待接单','已接单','已到场'].includes(incident.status)">
      <button
        v-if="incident.status !== '待接单'"
        type="button"
        class="m-btn m-btn--ghost"
        @click="escalateOpen = true"
      >升级事件</button>
      <button v-if="incident.status === '待接单'" type="button" class="m-btn m-btn--primary m-btn--block" :disabled="acting" @click="accept">接单</button>
      <button v-if="incident.status === '已接单'" type="button" class="m-btn m-btn--primary m-btn--block" :disabled="acting" @click="arrive">确认到场</button>
      <button v-if="incident.status === '已到场'" type="button" class="m-btn m-btn--primary m-btn--block" :disabled="acting" @click="startHandle">开始处理</button>
    </footer>

    <footer class="m-action-bar" v-else-if="incident.status === '处理中' && !showForm">
      <button type="button" class="m-btn m-btn--ghost" @click="escalateOpen = true">升级事件</button>
      <button type="button" class="m-btn m-btn--primary m-btn--block" @click="showForm = true">继续现场处置</button>
    </footer>

    <MobileEscalateDialog v-model="escalateOpen" :current-risk="incident.risk" @confirm="onEscalate" />
  </div>
  <div v-else class="m-detail m-detail--missing">
    <p>正在加载告警详情，或该告警已同步清除。</p>
    <button type="button" class="m-btn m-btn--primary" @click="router.push('/mobile/alerts')">返回告警列表</button>
  </div>
</template>
