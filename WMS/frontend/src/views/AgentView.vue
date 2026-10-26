<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { agentStatus, agentChat, agentHistory, agentUtilization, agentAlerts, readAgentAlert, agentSchedules,
  saveAgentSchedule, runAgentSchedule, agentMails, retryAgentMail, agentWeights, saveAgentWeight,
  type AgentRow, type AgentStatus, type ChatResult, type Schedule, type Utilization, type AgentEvent } from '@/api/agent'

const auth = useAuthStore()
const route = useRoute()
const tab = ref('chat'), question = ref(''), busy = ref(false), loading = ref(false), error = ref('')
watch(() => route.query.tab, value => { if (['chat', 'schedules', 'alerts', 'utilization'].includes(String(value))) tab.value = String(value) }, { immediate: true })
const status = ref<AgentStatus>(), utilization = ref<Utilization>()
const chats = ref<{ question: string; result: ChatResult; expanded: number[] }[]>([])
const history = ref<AgentRow[]>([]), alerts = ref<AgentRow[]>([]), mails = ref<AgentRow[]>([]), weights = ref<AgentRow[]>([])
const schedules = ref<Schedule[]>([])
const dialog = ref(false), saving = ref(false), weightDialog = ref(false)
const manage = computed(() => auth.hasPermission('agent:manage'))
const editWeights = computed(() => auth.hasPermission('master:write'))
const defaults = (): Schedule => ({ name: '每日库存巡检', enabled: true, frequency: 'DAILY', intervalMinutes: 60,
  dailyTime: '08:00', emails: '', lowStock: true, replenishment: true, frozenStock: true, capacity: true,
  capacityPercent: 85, cooldownHours: 24 })
const form = reactive<Schedule>(defaults())
const samples = ['帮我找一下大概前天入库的那批钢材，专业名称记不清了', '当前有哪些库存风险？请说明数量和依据', '分析当前仓库库位占用率和承重利用率']
const text = (x: unknown) => x == null ? '—' : String(x)
const number = (x: unknown) => x == null ? '—' : Number(x).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const date = (x: unknown) => x ? new Date(String(x)).toLocaleString('zh-CN', { hour12: false }) : '—'
const stateLabels: Record<string, string> = { COMPLETED: '已完成', DEGRADED: '降级 / 可重试', RUNNING: '处理中', OPEN: '待处理',
  RESOLVED: '已恢复', SENT: '已发送', PENDING: '等待发送', RETRY: '等待重试', FAILED: '发送失败', UNCONFIGURED: '邮件待配置',
  SENDING: '发送中', NORMAL: '正常', EMPTY: '空闲', HIGH: '承重占用偏高', OVERLOADED: '超过承重', MISSING_WEIGHT: '重量缺失', MISSING_CAPACITY: '承重未配置', STAGING: '暂存库位' }
const label = (x: unknown) => stateLabels[text(x)] ?? text(x)
function rows(event: AgentEvent): AgentRow[] { return Array.isArray(event.result) ? event.result as AgentRow[] : [] }
function eventError(event: AgentEvent): string { return event.result && typeof event.result === 'object' && 'error' in event.result ? text(event.result.error) : '' }
function metric(event: AgentEvent): Utilization | undefined { return event.tool === 'analyze_locations' ? event.result as Utilization : undefined }
function reasons(value: unknown): string { return Array.isArray(value) ? value.join('；') : '' }
function failure(reason: unknown) { ElMessage.error(reason instanceof Error ? reason.message : '操作失败，请重试') }
async function refresh() {
  loading.value = true; error.value = ''
  const results = await Promise.allSettled([
    agentStatus().then(v => status.value = v), agentHistory().then(v => history.value = v),
    agentAlerts().then(v => alerts.value = v), agentSchedules().then(v => schedules.value = v),
    agentMails().then(v => mails.value = v), agentUtilization().then(v => utilization.value = v),
  ])
  const failed = results.find(r => r.status === 'rejected')
  if (failed?.status === 'rejected') error.value = failed.reason instanceof Error ? failed.reason.message : '部分数据加载失败，请刷新重试'
  loading.value = false; window.dispatchEvent(new Event('wms-agent-alerts'))
}
async function send() {
  const q = question.value.trim(); if (!q || busy.value) return
  busy.value = true
  try {
    const result = await agentChat(q, chats.value.slice(-8).map(x => x.question))
    chats.value.push({ question: q, result, expanded: result.events.map((_, n) => n) }); question.value = ''; history.value = await agentHistory()
  } catch (e) { failure(e) } finally { busy.value = false }
}
function openSchedule(row?: Schedule) {
  Object.keys(form).forEach(key => delete (form as unknown as AgentRow)[key])
  Object.assign(form, defaults(), row ?? {}); if (form.emails === '-') form.emails = ''; dialog.value = true
}
async function saveSchedule() {
  saving.value = true
  try { await saveAgentSchedule(form); dialog.value = false; ElMessage.success('巡检任务已保存，服务器按北京时间执行'); await refresh() }
  catch(e) { failure(e) } finally { saving.value = false }
}
async function run(id: number) {
  saving.value = true
  try { const result = await runAgentSchedule(id); ElMessage.success(`巡检完成：${result.findings} 项风险，${result.newNotifications} 条新提醒`); await refresh() }
  catch(e) { failure(e) } finally { saving.value = false }
}
async function markRead(id: number) { try { await readAgentAlert(id); await refresh() } catch(e) { failure(e) } }
async function retry(id: number) { try { await retryAgentMail(id); ElMessage.success('已加入发送队列'); await refresh() } catch(e) { failure(e) } }
async function showWeights() { try { weights.value = await agentWeights(); weightDialog.value = true } catch(e) { failure(e) } }
async function saveWeight(row: AgentRow) {
  try { await saveAgentWeight(Number(row.id), Number(row.weight_kg)); ElMessage.success('每单位重量已保存'); await refresh() } catch(e) { failure(e) }
}
onMounted(refresh)
</script>

<template>
  <div class="page-stack agent-page">
    <header class="page-heading"><div><p class="eyebrow">WAREHOUSE AGENT</p><h1>仓储智能体</h1><p>用日常语言找货，自动巡检库存，了解库位使用情况。</p></div><el-button :loading="loading" @click="refresh">刷新</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="agent-status"><el-tag :type="status?.modelConfigured ? 'success' : 'warning'">{{ status?.modelConfigured ? status.model : '模型待配置' }}</el-tag><el-tag :type="status?.mailConfigured ? 'success' : 'warning'">{{ status?.mailConfigured ? '邮件通道已配置' : '邮件待配置 · 站内预警可用' }}</el-tag><span>当前范围：{{ auth.user?.tenant.currentWarehouse.name }} / {{ auth.user?.tenant.currentOwner.name }}</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="智能查货与分析" name="chat">
        <section class="panel chat-input"><h2>想查什么？直接说出来</h2><div class="prompt-chips"><el-button v-for="sample in samples" :key="sample" size="small" @click="question = sample">{{ sample }}</el-button></div>
          <el-input v-model="question" type="textarea" :rows="3" maxlength="2000" show-word-limit placeholder="例如：前几天来了一批成捆的钢材，好像是前天，帮我找找。补充供应商、外形或规格可以缩小范围。" />
          <div class="chat-actions"><small>查询不会修改库存。匹配结果来自业务记录，相关度不是概率。</small><el-button @click="chats = []">新对话</el-button><el-button type="primary" :loading="busy" :disabled="!question.trim()" @click="send">{{ busy ? '正在查询与分析' : '发送给智能体' }}</el-button></div>
        </section>
        <article v-for="(chat, index) in chats" :key="index" class="panel conversation">
          <h3>{{ chat.question }}</h3><el-tag :type="chat.result.state === 'COMPLETED' ? 'success' : 'warning'">{{ label(chat.result.state) }}</el-tag>
          <p>以下表格和指标直接来自业务查询，是单号、批次、数量与相关度的核对依据。</p>
          <el-collapse v-model="chat.expanded"><el-collapse-item v-for="(event, n) in chat.result.events" :key="n" :name="n" :title="`${n + 1}. ${event.label} · 查看真实数据和查询条件`">
            <pre class="tool-arguments">{{ event.arguments }}</pre><el-alert v-if="eventError(event)" :title="eventError(event)" type="warning" :closable="false" />
            <template v-else-if="event.tool === 'search_receipts' || event.tool === 'search_inventory'">
              <el-table :data="rows(event)" stripe empty-text="未找到匹配记录，请补充线索或调整日期范围">
                <el-table-column prop="relevance" label="相关度" width="90" /><el-table-column prop="good_name" label="货品" min-width="160" /><el-table-column prop="specification" label="规格" min-width="130" /><el-table-column prop="batch_code" label="批次" min-width="120" />
                <el-table-column prop="order_code" label="入库单号" min-width="130" /><el-table-column prop="received_at" label="实际收货时间" min-width="160" />
                <el-table-column label="收货 / 可用"><template #default="s">{{ number(s.row.received_qty) }} / {{ number(s.row.available_qty) }} {{ text(s.row.unit) }}</template></el-table-column>
                <el-table-column prop="current_locations" label="当前库位" min-width="120" /><el-table-column label="供应商" min-width="180"><template #default="s">{{ text(s.row.supplier_code) }}<small>{{ text(s.row.partner_name) }}</small></template></el-table-column>
                <el-table-column label="匹配依据" min-width="230"><template #default="s">{{ reasons(s.row.match_reasons) }}<small>{{ s.row.search_notice }}</small></template></el-table-column>
              </el-table>
            </template>
            <template v-else-if="metric(event)"><p>{{ metric(event)?.note }}</p><p>占用库位 {{ metric(event)?.occupied_locations }} / {{ metric(event)?.total_locations }}，占用率 {{ number(metric(event)?.occupancy_percent) }}%</p><el-button @click="tab = 'utilization'">查看库位分析面板</el-button></template>
            <el-table v-else :data="rows(event)" stripe><el-table-column prop="good_name" label="货品" /><el-table-column prop="available_qty" label="可用量" /><el-table-column prop="threshold_qty" label="安全库存" /><el-table-column prop="frozen_qty" label="冻结量" /></el-table>
          </el-collapse-item></el-collapse>
          <h4>AI 解读</h4><small>模型可能出现理解或引用错误；单号、数量、日期及相关度以查询结果表格为准。</small>
          <p class="agent-answer">{{ chat.result.answer }}</p>
        </article>
        <section v-if="!chats.length" class="panel"><h2>最近任务</h2><el-table :data="history" empty-text="还没有查询任务"><el-table-column prop="question" label="问题" min-width="240" /><el-table-column label="状态" width="160"><template #default="s">{{ label(s.row.state) }}</template></el-table-column><el-table-column label="时间" min-width="180"><template #default="s">{{ date(s.row.created_at) }}</template></el-table-column><el-table-column label="操作"><template #default="s"><el-button link @click="question = text(s.row.question)">再次查询</el-button></template></el-table-column></el-table></section>
      </el-tab-pane>
      <el-tab-pane label="定时巡检" name="schedules">
        <section class="panel"><div class="section-actions"><div><h2>服务器自动检查</h2><p>即使关闭浏览器仍会运行。站内提醒发送给任务创建人，指定邮箱接收同一范围的预警。</p></div><el-button v-if="manage" type="primary" @click="openSchedule()">新建巡检任务</el-button></div>
          <el-table :data="schedules" stripe empty-text="尚未配置巡检任务"><el-table-column prop="name" label="名称" min-width="160" /><el-table-column label="状态"><template #default="s">{{ s.row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="执行频率" min-width="140"><template #default="s">{{ s.row.frequency === 'DAILY' ? `每日 ${s.row.dailyTime}` : `每 ${s.row.intervalMinutes} 分钟` }}</template></el-table-column><el-table-column prop="emails" label="指定邮箱" min-width="200" /><el-table-column label="下次运行" min-width="180"><template #default="s">{{ s.row.enabled ? date(s.row.nextRunAt) : '已停用' }}</template></el-table-column><el-table-column label="上次运行" min-width="180"><template #default="s">{{ date(s.row.lastRunAt) }}</template></el-table-column><el-table-column prop="lastError" label="运行提示" min-width="160" /><el-table-column v-if="manage" label="操作" width="170"><template #default="s"><el-button link @click="openSchedule(s.row)">编辑</el-button><el-button link type="primary" :loading="saving" @click="run(s.row.id)">立即巡检</el-button></template></el-table-column></el-table>
        </section>
      </el-tab-pane>
      <el-tab-pane :label="`站内预警 ${alerts.filter(x => !x.read_at && x.state === 'OPEN').length}`" name="alerts">
        <section class="panel"><h2>库存风险与恢复记录</h2><el-table :data="alerts" stripe empty-text="暂无预警，配置巡检任务后可立即运行一次"><el-table-column prop="title" label="预警" min-width="180" /><el-table-column prop="body" label="依据与建议" min-width="360" /><el-table-column label="状态"><template #default="s">{{ label(s.row.state) }} / {{ s.row.read_at ? '已读' : '未读' }}</template></el-table-column><el-table-column label="更新时间" min-width="180"><template #default="s">{{ date(s.row.updated_at) }}</template></el-table-column><el-table-column label="操作"><template #default="s"><el-button v-if="!s.row.read_at" link @click="markRead(s.row.id)">标记已读</el-button></template></el-table-column></el-table></section>
        <section class="panel"><h2>邮件发送记录</h2><p>站内告警与邮件队列独立。发送失败自动重试，最多5次；问题修复后可手动重试。</p><el-table :data="mails" stripe empty-text="尚无邮件发送记录"><el-table-column prop="subject" label="主题" min-width="180" /><el-table-column prop="recipients" label="接收邮箱" min-width="200" /><el-table-column label="状态" min-width="140"><template #default="s">{{ label(s.row.state) }}</template></el-table-column><el-table-column prop="attempts" label="尝试次数" /><el-table-column prop="last_error" label="提示" min-width="230" /><el-table-column label="操作"><template #default="s"><el-button v-if="manage && ['FAILED','UNCONFIGURED','RETRY'].includes(s.row.state)" link @click="retry(s.row.id)">重新发送</el-button></template></el-table-column></el-table></section>
      </el-tab-pane>
      <el-tab-pane label="库位利用率" name="utilization">
        <template v-if="utilization"><section class="metric-grid"><article><span>正常库位</span><strong>{{ utilization.total_locations }}</strong><small>不含系统暂存库位</small></article><article><span>库位占用率</span><strong>{{ number(utilization.occupancy_percent) }}%</strong><small>{{ utilization.occupied_locations }} 个库位有库存</small></article><article><span>已知库存重量</span><strong>{{ number(utilization.known_weight_kg) }}</strong><small>kg · 不含暂存库位</small></article><article class="warning"><span>缺失重量记录</span><strong>{{ utilization.missing_weight_rows }}</strong><small>缺失时不推算完整承重利用率</small></article></section>
          <section class="panel"><div class="section-actions"><h2>库区与库位</h2><el-button @click="showWeights">查看 / 维护每单位重量</el-button></div><el-alert :title="utilization.note" type="info" :closable="false" show-icon />
            <el-table :data="utilization.locations" stripe><el-table-column prop="code" label="库位" /><el-table-column prop="area_name" label="库区" /><el-table-column label="已知重量 kg"><template #default="s">{{ number(s.row.known_weight_kg) }}</template></el-table-column><el-table-column label="承重上限 kg"><template #default="s">{{ number(s.row.max_weight_kg) }}</template></el-table-column><el-table-column label="承重占比" min-width="150"><template #default="s"><el-progress v-if="s.row.utilization_percent != null" :percentage="Math.min(100, Number(s.row.utilization_percent))" :status="Number(s.row.utilization_percent) > 100 ? 'exception' : undefined" />{{ s.row.utilization_percent == null ? '无法计算' : number(s.row.utilization_percent) + '%' }}</template></el-table-column><el-table-column label="状态" min-width="140"><template #default="s">{{ label(s.row.status) }}</template></el-table-column></el-table>
          </section><section class="panel"><h2>利用率历史快照</h2><p>每次巡检保存快照，从功能上线后开始积累，不补造历史数据。</p><el-table :data="utilization.history" empty-text="暂无快照，请运行一次巡检"><el-table-column label="时间"><template #default="s">{{ date(s.row.created_at) }}</template></el-table-column><el-table-column prop="occupied_locations" label="占用库位" /><el-table-column prop="total_locations" label="总库位" /><el-table-column prop="known_weight_kg" label="已知重量 kg" /><el-table-column prop="unknown_weight_rows" label="缺失重量记录" /></el-table></section>
        </template>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="dialog" :title="form.id ? '编辑巡检任务' : '新建巡检任务'" width="min(680px, 94vw)">
      <el-form label-position="top"><el-form-item label="任务名称"><el-input v-model="form.name" maxlength="100" /></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item><div class="form-grid"><el-form-item label="执行频率"><el-select v-model="form.frequency"><el-option label="每天固定时间" value="DAILY" /><el-option label="按间隔执行" value="INTERVAL" /></el-select></el-form-item><el-form-item v-if="form.frequency === 'DAILY'" label="北京时间"><el-time-select v-model="form.dailyTime" start="00:00" end="23:45" step="00:15" /></el-form-item><el-form-item v-else label="间隔分钟（5～10080）"><el-input-number v-model="form.intervalMinutes" :min="5" :max="10080" /></el-form-item></div>
        <el-form-item label="指定接收邮箱（多个用逗号分隔，最多5个；留空仅站内通知）"><el-input v-model="form.emails" maxlength="1000" placeholder="warehouse@example.com" /></el-form-item><el-form-item label="巡检规则"><el-checkbox v-model="form.lowStock">低库存</el-checkbox><el-checkbox v-model="form.replenishment">库位补货</el-checkbox><el-checkbox v-model="form.frozenStock">冻结库存</el-checkbox><el-checkbox v-model="form.capacity">承重及数据缺失</el-checkbox></el-form-item><div class="form-grid"><el-form-item label="承重提醒阈值 %"><el-input-number v-model="form.capacityPercent" :min="1" :max="150" /></el-form-item><el-form-item label="同一问题重复提醒间隔（小时）"><el-input-number v-model="form.cooldownHours" :min="1" :max="168" /></el-form-item></div><el-alert title="通知发给当前任务创建人及指定邮箱，查询范围固定为创建时选择的仓库与货主。" type="info" :closable="false" /></el-form><template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveSchedule">保存任务</el-button></template>
    </el-dialog>
    <el-dialog v-model="weightDialog" title="每单位重量（kg）" width="min(800px,94vw)"><el-alert title="钢材按捆录入时，填写每捆重量；不同单位、不同货品不要共用重量。重量变更会影响后续分析。" type="info" :closable="false" /><el-table :data="weights"><el-table-column prop="name" label="货品" /><el-table-column prop="unit" label="库存单位" /><el-table-column label="每单位重量 kg" min-width="180"><template #default="s"><el-input-number v-if="editWeights" v-model="s.row.weight_kg" :min="0.001" :precision="3" /><span v-else>{{ number(s.row.weight_kg) }}</span></template></el-table-column><el-table-column v-if="editWeights" label="操作"><template #default="s"><el-button link @click="saveWeight(s.row)">保存</el-button></template></el-table-column></el-table></el-dialog>
  </div>
</template>

<style scoped>
.agent-status,.prompt-chips,.chat-actions,.section-actions {display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.agent-status{margin-bottom:8px;font-size:13px}.prompt-chips{margin-bottom:18px}.chat-input,.conversation{padding:24px;margin-bottom:18px}
.chat-actions{margin-top:14px;justify-content:flex-end}.chat-actions small{margin-right:auto}.agent-answer{white-space:pre-wrap;line-height:1.85;overflow-wrap:anywhere}
.tool-arguments{white-space:pre-wrap;font-size:12px;opacity:.75}.section-actions{justify-content:space-between;margin-bottom:16px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px}
.agent-page .panel{padding:22px;margin-bottom:18px}.agent-page h2{font-size:18px}.agent-page .el-table{margin-top:16px}.agent-page small{display:block}
@media(max-width:700px){.form-grid{grid-template-columns:1fr}.prompt-chips .el-button{height:auto;white-space:normal;padding:10px}.chat-actions{justify-content:stretch}.chat-actions .el-button{flex:1}}
</style>
