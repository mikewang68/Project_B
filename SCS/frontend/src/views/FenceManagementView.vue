<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, RefreshRight } from '@element-plus/icons-vue'
import SafetyMapCanvas from '@/components/shared/SafetyMapCanvas.vue'
import FenceList from '@/components/fence/FenceList.vue'
import FenceDetailPanel from '@/components/fence/FenceDetailPanel.vue'
import FencePublishDialog from '@/components/fence/FencePublishDialog.vue'
import type { FenceDraft, FencePoint, FenceRecord } from '@/types/fence'
import { draftToPayload, fenceApi } from '@/api/fences'
import { isDomainLiveEvent, sharedLiveSocket } from '@/api/live'

const fences = ref<FenceRecord[]>([])
const selectedId = ref('FENCE-001')
const editing = ref(false)
const editingId = ref<string | null>(null)
const editPoints = ref<FencePoint[]>([])
const publishOpen = ref(false)
const draft = reactive<FenceDraft>({
  name: '',
  kind: '临时围栏',
  riskLevel: '一般',
  teams: '装卸一班',
  startsAt: '2026-09-03 08:00',
  endsAt: '2026-09-03 18:00',
  bufferDistance: 5.0,
})
let disposeLive: (() => void) | undefined
const selectedFence = computed(() => fences.value.find((fence) => fence.id === selectedId.value))
const isEditingExisting = computed(() => Boolean(editingId.value))

function computeBufferPolygon(points: FencePoint[], bufferDist: number): FencePoint[] {
  if (!points || points.length < 3) return []
  const n = points.length
  let cx = 0
  let cy = 0
  for (const p of points) {
    cx += p.x
    cy += p.y
  }
  cx /= n
  cy /= n
  const scale = 0.45 * Math.max(0.5, bufferDist)
  return points.map((p) => {
    const dx = p.x - cx
    const dy = p.y - cy
    const len = Math.hypot(dx, dy)
    if (len < 0.001) return { x: p.x, y: p.y }
    return {
      x: Math.max(1, Math.min(99, Number((p.x + (dx / len) * scale).toFixed(1)))),
      y: Math.max(1, Math.min(99, Number((p.y + (dy / len) * scale).toFixed(1)))),
    }
  })
}

const editBufferPoints = computed(() => computeBufferPolygon(editPoints.value, draft.bufferDistance ?? 5.0))

async function loadFences(): Promise<void> {
  try {
    fences.value = await fenceApi.list()
    if (!selectedFence.value && fences.value.length) selectedId.value = fences.value[0]!.id
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '围栏数据加载失败')
  }
}

function upsert(record: FenceRecord): void {
  const idx = fences.value.findIndex((f) => f.id === record.id)
  if (idx >= 0) fences.value.splice(idx, 1, record)
  else fences.value.unshift(record)
  selectedId.value = record.id
}

function selectFence(fence: FenceRecord): void {
  selectedId.value = fence.id
  editing.value = false
  editingId.value = null
  editPoints.value = []
}

function createFence(): void {
  editing.value = true
  editingId.value = null
  selectedId.value = ''
  editPoints.value = []
  Object.assign(draft, {
    name: '',
    kind: '临时围栏',
    riskLevel: '一般',
    teams: '装卸一班',
    startsAt: '2026-09-03 08:00',
    endsAt: '2026-09-03 18:00',
    bufferDistance: 5.0,
  })
}

function editFence(fence: FenceRecord): void {
  editing.value = true
  editingId.value = fence.id
  selectedId.value = fence.id
  editPoints.value = fence.polygon.map((p) => ({ ...p }))
  Object.assign(draft, {
    name: fence.name,
    kind: fence.kind,
    riskLevel: fence.riskLevel ?? '一般',
    teams: fence.teams,
    startsAt: fence.effectiveAt,
    endsAt: fence.expiresAt,
    bufferDistance: fence.bufferDistance ?? 5.0,
  })
}

function cancelEdit(): void {
  editing.value = false
  editingId.value = null
  editPoints.value = []
}

async function ensureDraft(status: '草稿' | '待评审'): Promise<void> {
  if (editPoints.value.length < 3 || !draft.name.trim()) {
    ElMessage.warning('请填写围栏名称，并在地图上添加至少 3 个边界点')
    return
  }
  try {
    const payload = {
      ...draftToPayload({ ...draft, polygon: [...editPoints.value] }),
      submitReview: status === '待评审',
    }
    let record: FenceRecord
    if (editingId.value) {
      record = await fenceApi.update(editingId.value, payload)
      ElMessage.success('围栏修改已保存')
    } else {
      record = await fenceApi.create(payload)
      ElMessage.success(status === '草稿' ? '围栏草稿已保存' : '围栏已提交评审')
    }
    upsert(record)
    editing.value = false
    editingId.value = null
    editPoints.value = []
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '保存失败')
  }
}

function updateDraft(value: FenceDraft): void { Object.assign(draft, value) }

async function submitSelectedReview(): Promise<void> {
  const fence = selectedFence.value
  if (!fence) return
  try {
    upsert(await fenceApi.submitReview(fence.id))
    ElMessage.success(`${fence.id} 已提交评审`)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '提交评审失败')
  }
}
async function simulateFail(): Promise<void> {
  const fence = selectedFence.value; if (!fence) return
  try {
    upsert(await fenceApi.simulateMismatch(fence.id))
    ElMessage.warning('EDGE-03 当前规则版本与平台不一致，请重新下发')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '模拟版本异常失败')
  }
}
async function retry(): Promise<void> {
  const fence = selectedFence.value; if (!fence) return
  try {
    upsert(await fenceApi.redeliver(fence.id, 'EDGE-03'))
    ElMessage.success('EDGE-03 重新下发成功，4 / 4 节点已同步')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '重新下发失败')
  }
}
async function published(): Promise<void> {
  const fence = selectedFence.value; if (!fence) return
  try {
    upsert(await fenceApi.publish(fence.id))
    ElMessage.success('围栏已发布，4 / 4 边缘节点下发成功')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '发布失败')
  }
}

onMounted(() => {
  void loadFences()
  sharedLiveSocket.connect()
  disposeLive = sharedLiveSocket.onMessage((raw) => {
    if (isDomainLiveEvent(raw) && String(raw.type) === 'fence.changed') void loadFences()
  })
})
onBeforeUnmount(() => disposeLive?.())
</script>

<template>
  <section class="page-content fence-page">
    <header class="module-intro header-actions-only"><div class="fence-summary"><span><i></i><b>4 / 4</b>边缘节点在线</span><span>规则版本已受控</span></div></header>
    <div class="fence-workspace">
      <FenceList :fences="fences" :selected-id="selectedId" @select="selectFence" @create="createFence" />
      <article class="dashboard-card fence-map-card">
        <header class="workspace-card-header">
          <div>
            <span>FENCE PREVIEW</span>
            <h2>{{ editing ? (isEditingExisting ? '编辑围栏与隔离带' : '绘制围栏边界') : '围栏空间分布' }}</h2>
            <p>{{ editing ? '可点击增补顶点或在右侧调整外围逻辑缓冲区距离' : '选择左侧围栏查看对应范围、隔离带与规则状态' }}</p>
          </div>
          <button v-if="editing && editPoints.length" type="button" class="clear-point-button" @click="editPoints = []">
            <el-icon><Delete /></el-icon>清空点位
          </button>
        </header>
        <div class="fence-map-stage">
          <SafetyMapCanvas
            :fences="fences"
            :selected-fence-id="selectedId"
            :edit-points="editPoints"
            :edit-buffer-points="editBufferPoints"
            :editing="editing"
            :show-equipment="false"
            @select-fence="selectFence"
            @map-point="editPoints.push($event)"
          />
        </div>
      </article>
      <FenceDetailPanel
        :fence="selectedFence"
        :editing="editing"
        :is-editing-existing="isEditingExisting"
        :draft="draft"
        :point-count="editPoints.length"
        @update:draft="updateDraft"
        @edit="selectedFence && editFence(selectedFence)"
        @cancel="cancelEdit"
        @save="ensureDraft('草稿')"
        @review="editing ? ensureDraft('待评审') : submitSelectedReview()"
        @publish="publishOpen = true"
        @fail="simulateFail"
        @retry="retry"
      />
    </div>
    <div class="fence-page-note"><el-icon><RefreshRight /></el-icon><span><b>演示环境</b>围栏生命周期由 Spring Boot Backend Demo 驱动（InMemory），支持隔离带预警与边缘节点下发。</span></div>
    <FencePublishDialog v-model="publishOpen" :fence="selectedFence" @completed="published" />
  </section>
</template>
