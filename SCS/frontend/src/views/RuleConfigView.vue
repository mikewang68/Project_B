<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RuleOverview, { type RuleKpi } from '@/components/rules/RuleOverview.vue'
import RuleCategoryNav from '@/components/rules/RuleCategoryNav.vue'
import RuleToolbar from '@/components/rules/RuleToolbar.vue'
import RuleTable from '@/components/rules/RuleTable.vue'
import RuleDetailDrawer from '@/components/rules/RuleDetailDrawer.vue'
import RuleFormDrawer, { type RuleFormPayload } from '@/components/rules/RuleFormDrawer.vue'
import RuleSimulationDialog from '@/components/rules/RuleSimulationDialog.vue'
import RuleConflictDialog from '@/components/rules/RuleConflictDialog.vue'
import RulePublishDialog from '@/components/rules/RulePublishDialog.vue'
import RuleRollbackDialog from '@/components/rules/RuleRollbackDialog.vue'
import { ruleApi, type RuleRollbackResult } from '@/api/rules'
import type { MappedRuleConflict } from '@/adapters/rule'
import { isDomainLiveEvent, sharedLiveSocket } from '@/api/live'
import type { RuleStatus, SafetyRule } from '@/types/rule'

const route = useRoute()
const router = useRouter()
const rules = ref<SafetyRule[]>([])
const loading = ref(false)

// ---------- 筛选（变化后向后端重新请求） ----------
const category = ref('全部')
const keyword = ref('')
const statusFilter = ref('')
const riskFilter = ref('')

const filtered = computed(() => rules.value)

// 筛选条件变化后防抖向后端重新请求
watch([category, keyword, statusFilter, riskFilter], () => scheduleReload())

const metrics = ref({ active: 0, review: 0, draft: 0, mismatch: 0, changedToday: 0 })
const kpis = computed<RuleKpi[]>(() => [
  { key: 'active', label: '已生效规则', value: metrics.value.active, hint: '平台当前生效总量', tone: 'primary', icon: 'check' },
  { key: 'review', label: '待评审', value: metrics.value.review, hint: '等待审批流转', tone: 'warning', icon: 'review' },
  { key: 'draft', label: '草稿', value: metrics.value.draft, hint: '编辑中未提交', tone: 'muted', icon: 'draft' },
  { key: 'mismatch', label: '版本异常', value: metrics.value.mismatch, hint: '边缘节点版本不一致', tone: 'danger', icon: 'error' },
  { key: 'changed', label: '今日变更', value: metrics.value.changedToday, hint: '含新建 / 编辑 / 发布', tone: 'success', icon: 'change' },
])

let reloadTimer: ReturnType<typeof setTimeout> | undefined
async function reload(): Promise<void> {
  loading.value = true
  try {
    const [list, m] = await Promise.all([
      ruleApi.list({
        category: category.value === '全部' ? undefined : category.value,
        keyword: keyword.value.trim() || undefined,
        status: statusFilter.value || undefined,
        risk: riskFilter.value || undefined,
      }),
      ruleApi.metrics(),
    ])
    rules.value = list
    metrics.value = m
    if (current.value) {
      const latest = list.find((r) => r.id === current.value?.id)
      if (latest) current.value = latest
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '规则数据加载失败')
  } finally {
    loading.value = false
  }
}

function scheduleReload(): void {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(reload, 600)
}

// ---------- 详情 ----------
const detailOpen = ref(false)
const current = ref<SafetyRule>()
function openDetail(rule: SafetyRule): void {
  current.value = rule
  detailOpen.value = true
}
function upsert(rule: SafetyRule): void {
  const idx = rules.value.findIndex((r) => r.id === rule.id)
  if (idx >= 0) rules.value.splice(idx, 1, rule)
  else rules.value.unshift(rule)
  if (current.value?.id === rule.id) current.value = rule
}

// ---------- 新建 / 编辑 ----------
const formOpen = ref(false)
const editing = ref<SafetyRule>()
const newVersionMode = ref(false)
function openCreate(): void {
  editing.value = undefined
  newVersionMode.value = false
  formOpen.value = true
}
function openEdit(rule: SafetyRule, asNewVersion = false): void {
  editing.value = rule
  newVersionMode.value = asNewVersion
  formOpen.value = true
}

async function highRiskConfirm(title: string, message: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: '确认继续', cancelButtonText: '取消', type: 'warning',
      confirmButtonClass: 'el-button--danger', roundButton: true,
    })
    return true
  } catch { return false }
}

async function saveRule(payload: RuleFormPayload, submitReview: boolean): Promise<void> {
  if (payload.highRisk && submitReview) {
    const ok = await highRiskConfirm('高危安全参数确认', '该规则涉及高危安全参数，提交后将进入审批并可能影响现场设备联动，确认提交？')
    if (!ok) { ElMessage.info('已取消提交'); return }
  }
  const body = {
    name: payload.name,
    category: payload.category,
    areas: [...payload.areas],
    risk: payload.risk,
    owner: payload.owner,
    params: payload.params.map((p) => ({ label: p.label, value: p.value, danger: p.danger })),
    actions: [...payload.actions],
    highRisk: payload.highRisk,
    submitReview,
  }
  try {
    let saved: SafetyRule
    if (payload.editId) {
      saved = await ruleApi.update(payload.editId, { ...body, asNewVersion: newVersionMode.value })
    } else {
      saved = await ruleApi.create(body)
    }
    upsert(saved)
    category.value = '全部'
    await reload()
    ElMessage.success(submitReview ? (payload.editId ? '已提交评审' : '规则已创建并提交评审') : payload.editId ? '草稿已保存' : '草稿规则已创建')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

// ---------- 状态机操作 ----------
async function handleAction(action: string, rule: SafetyRule): Promise<void> {
  try {
    switch (action) {
      case '编辑': openEdit(rule); break
      case '提交评审': {
        if (rule.highRisk) {
          const ok = await highRiskConfirm('高危安全参数确认', '该规则涉及高危安全参数，提交后将进入审批流程，确认提交？')
          if (!ok) return
        }
        upsert(await ruleApi.submit(rule.id, { confirmHighRisk: true }))
        ElMessage.success(`「${rule.name}」已提交评审`)
        break
      }
      case '批准': {
        if (rule.highRisk) {
          const ok = await highRiskConfirm('高危安全参数批准', '该规则包含高危安全参数，批准后可发布并影响现场联动，确认批准？')
          if (!ok) return
        }
        upsert(await ruleApi.approve(rule.id, { confirmHighRisk: rule.highRisk }))
        ElMessage.success('评审通过，规则已批准，可发布')
        break
      }
      case '驳回':
        upsert(await ruleApi.reject(rule.id))
        ElMessage.info('已驳回，规则退回草稿')
        break
      case '发布':
      case '重新下发':
        publishMode.value = action === '重新下发' ? 'redeliver' : 'publish'
        publishing.value = rule
        publishOpen.value = true
        break
      case '新建版本': openEdit(rule, true); break
      case '回滚': rollbackRule.value = rule; rollbackOpen.value = true; break
      case '停用':
        upsert(await ruleApi.disable(rule.id))
        ElMessage.info('规则已停用')
        break
      default: break
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

const publishMode = ref<'publish' | 'redeliver'>('publish')
async function onPublished(): Promise<void> {
  if (!publishing.value) return
  const target = publishing.value
  try {
    const saved = publishMode.value === 'redeliver'
      ? await ruleApi.redeliver(target.id)
      : await ruleApi.publish(target.id)
    upsert(saved)
    ElMessage.success(`${saved.edgeNodes.length} / ${saved.edgeNodes.length} 节点同步成功，规则已生效`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  }
}

async function onRollback(rule: SafetyRule, targetVersion: string): Promise<void> {
  try {
    const res: RuleRollbackResult = await ruleApi.rollback(rule.id, targetVersion)
    upsert(res.rule)
    current.value = res.rule
    ElMessage.success(`已生成回滚版本 ${res.newVersion}，请重新审批发布`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回滚失败')
  }
}

// ---------- 仿真 ----------
const simOpen = ref(false)
const simInitial = ref<string>()
function openSim(rule?: SafetyRule): void {
  simInitial.value = rule?.id
  simOpen.value = true
}

// ---------- 冲突 ----------
const conflictOpen = ref(false)
const conflicts = ref<MappedRuleConflict[]>([])
async function runConflictCheck(): Promise<void> {
  try {
    conflicts.value = await ruleApi.conflictCheck()
    conflictOpen.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '冲突检查失败')
  }
}
function viewConflict(c: { ruleA: string }): void {
  conflictOpen.value = false
  const target = rules.value.find((r) => r.id === c.ruleA)
  if (target) openDetail(target)
}
function ignoreConflict(): void { ElMessage.warning('已忽略冲突并保存，建议发布前再次复核') }

// ---------- 版本异常 ----------
async function simulateMismatch(): Promise<void> {
  try {
    const rule = await ruleApi.simulateMismatch('RULE-PER-001')
    upsert(rule)
    ElMessage.warning('EDGE-03 当前规则版本与平台不一致。')
    openDetail(rule)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '模拟版本异常失败')
  }
}

// ---------- 发布 / 回滚 Dialog ----------
const publishOpen = ref(false)
const publishing = ref<SafetyRule>()
const rollbackOpen = ref(false)
const rollbackRule = ref<SafetyRule>()

// ---------- WebSocket：规则变化后防抖刷新 ----------
const offMessage = sharedLiveSocket.onMessage((raw) => {
  if (isDomainLiveEvent(raw) && String(raw.type).startsWith('rule.')) scheduleReload()
})

// ---------- 告警中心规则编号跳转关联 ----------
onMounted(async () => {
  sharedLiveSocket.connect()
  await reload()
  const q = route.query.rule
  if (typeof q === 'string') {
    try {
      const target = await ruleApi.detail(q)
      upsert(target)
      setTimeout(() => openDetail(target), 300)
    } catch {
      // 规则不存在时忽略跳转
    }
  }
})
// 离开页面时清空路由上的 rule 查询参数（组件内守卫随卸载自动移除）
onBeforeRouteLeave(() => { router.replace({ query: {} }); return true })
onUnmounted(() => {
  offMessage()
  if (reloadTimer) clearTimeout(reloadTimer)
})
</script>

<template>
  <section class="page-content rules-page" v-loading="loading">
    <RuleOverview :items="kpis" />

    <RuleToolbar v-model:keyword="keyword" v-model:status="statusFilter" v-model:risk="riskFilter"
      @create="openCreate" @simulate="openSim()" @conflict="runConflictCheck" @mismatch="simulateMismatch" />

    <div class="rules-body">
      <RuleCategoryNav :rules="rules" :active="category" @select="category = $event; reload()" />
      <RuleTable :rules="filtered" @detail="openDetail" @simulate="openSim" />
    </div>

    <RuleDetailDrawer v-model="detailOpen" :rule="current"
      @edit="openEdit" @simulate="openSim" @action="handleAction" />
    <RuleFormDrawer v-model="formOpen" :rule="editing" @save="saveRule" />
    <RuleSimulationDialog v-model="simOpen" :rules="rules" :initial-id="simInitial" />
    <RuleConflictDialog v-model="conflictOpen" :conflicts="conflicts" @view="viewConflict" @ignore="ignoreConflict" />
    <RulePublishDialog v-model="publishOpen" :rule="publishing" @done="onPublished" />
    <RuleRollbackDialog v-model="rollbackOpen" :rule="rollbackRule" @confirm="onRollback" />
  </section>
</template>
