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
const selectedId = ref('FENCE-001'), editing = ref(false), editPoints = ref<FencePoint[]>([]), publishOpen = ref(false)
const draft = reactive<FenceDraft>({ name: '', kind: '临时围栏', riskLevel: '一般', teams: '装卸一班', startsAt: '2026-09-03 08:00', endsAt: '2026-09-03 18:00' })
let disposeLive: (() => void) | undefined
const selectedFence = computed(() => fences.value.find((fence) => fence.id === selectedId.value))

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

function selectFence(fence: FenceRecord): void { selectedId.value = fence.id; editing.value = false; editPoints.value = [] }
function createFence(): void { editing.value = true; selectedId.value = ''; editPoints.value = []; Object.assign(draft, { name: '', kind: '临时围栏', riskLevel: '一般', teams: '装卸一班', startsAt: '2026-09-03 08:00', endsAt: '2026-09-03 18:00' }) }

async function ensureDraft(status: '草稿' | '待评审'): Promise<void> {
  if (editPoints.value.length < 3 || !draft.name.trim()) { ElMessage.warning('请填写围栏名称，并在地图上添加至少 3 个边界点'); return }
  try {
    const payload = { ...draftToPayload({ ...draft, polygon: [...editPoints.value] }), submitReview: status === '待评审' }
    const record = await fenceApi.create(payload)
    upsert(record)
    editing.value = false
    ElMessage.success(status === '草稿' ? '围栏草稿已保存' : '围栏已提交评审')
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
        <header class="workspace-card-header"><div><span>FENCE PREVIEW</span><h2>{{ editing ? '绘制围栏边界' : '围栏空间分布' }}</h2><p>{{ editing ? '点击站场地图添加顶点，至少需要 3 个点' : '选择左侧围栏查看对应范围与规则状态' }}</p></div><button v-if="editing && editPoints.length" type="button" class="clear-point-button" @click="editPoints = []"><el-icon><Delete /></el-icon>清空点位</button></header>
        <div class="fence-map-stage"><SafetyMapCanvas :fences="fences" :selected-fence-id="selectedId" :edit-points="editPoints" :editing="editing" :show-equipment="false" @select-fence="selectFence" @map-point="editPoints.push($event)" /></div>
      </article>
      <FenceDetailPanel :fence="selectedFence" :editing="editing" :draft="draft" :point-count="editPoints.length" @update:draft="updateDraft" @save="ensureDraft('草稿')" @review="editing ? ensureDraft('待评审') : submitSelectedReview()" @publish="publishOpen = true" @fail="simulateFail" @retry="retry" />
    </div>
    <div class="fence-page-note"><el-icon><RefreshRight /></el-icon><span><b>演示环境</b>围栏生命周期由 Spring Boot Backend Demo 驱动（InMemory），边缘节点下发为模拟状态。</span></div>
    <FencePublishDialog v-model="publishOpen" :fence="selectedFence" @completed="published" />
  </section>
</template>
