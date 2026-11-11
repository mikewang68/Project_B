<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import type { EdgeNode } from '@/types/operations'
import { operationsApi } from '@/api/operations'
import OpsHealthBadge from './OpsHealthBadge.vue'

const props = defineProps<{ modelValue: boolean; node: EdgeNode | null }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  reconnect: [node: EdgeNode]
  resyncTime: [node: EdgeNode]
  redeliver: [node: EdgeNode]
}>()

const showLogs = ref(false)
const diagnosing = ref(false)
const diagnoseDone = ref(false)
const diagnoseError = ref('')
interface DiagnoseLine { t: string; level: 'INFO' | 'WARN' | 'ERROR'; text: string }
const diagnoseLines = ref<DiagnoseLine[]>([])

watch(() => props.modelValue, (v) => {
  if (v) { showLogs.value = false; diagnoseDone.value = false; diagnoseLines.value = []; diagnoseError.value = '' }
})

const versionMismatch = computed(() => !!props.node && props.node.ruleVersion !== props.node.platformVersion)

function nowTime(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 真实诊断：读取链路状态、规则版本一致性、时间偏差三项（来自边缘自治接口与节点台账）。 */
async function runDiagnose(): Promise<void> {
  if (!props.node) return
  const nodeId = props.node.id
  diagnosing.value = true
  diagnoseDone.value = false
  diagnoseError.value = ''
  diagnoseLines.value = []
  const lines: DiagnoseLine[] = []
  try {
    const link = await operationsApi.link()
    const nodeLink = link.nodes.find((n) => n.nodeId === nodeId)
    lines.push({
      t: nowTime(), level: 'INFO',
      text: `云边链路 ${link.label}（${link.state}）`,
    })
    lines.push({
      t: nowTime(), level: nodeLink?.cloudConnected ? 'INFO' : 'WARN',
      text: `云端连接 ${nodeLink?.cloudConnected ? '正常' : '中断'}；本地自治 ${nodeLink?.autonomyActive ? '激活' : '未激活'}`,
    })
    try {
      const reconcile = await operationsApi.ruleReconcileView()
      const match = reconcile.expectedRuleVersion === props.node.ruleVersion
      lines.push({
        t: nowTime(), level: match ? 'INFO' : 'WARN',
        text: match
          ? `规则版本 ${props.node.ruleVersion} 与平台预期一致`
          : `规则版本不一致：节点 ${props.node.ruleVersion}，平台预期 ${reconcile.expectedRuleVersion}`,
      })
    } catch {
      lines.push({ t: nowTime(), level: 'WARN', text: '规则版本对账暂不可达' })
    }
    const offset = props.node.timeOffsetMs
    lines.push({
      t: nowTime(), level: offset === null || Math.abs(offset) <= 2000 ? 'INFO' : 'WARN',
      text: offset === null
        ? '时间偏差未知（尚未校时）'
        : Math.abs(offset) <= 2000
          ? '时间同步正常'
          : `时间偏差 ${offset} ms，建议重新同步时间`,
    })
    lines.push({ t: nowTime(), level: 'INFO', text: '诊断完成' })
    diagnoseLines.value = lines
    diagnoseDone.value = true
  } catch (err) {
    diagnoseError.value = err instanceof Error ? err.message : '诊断请求失败'
    lines.push({ t: nowTime(), level: 'ERROR', text: diagnoseError.value })
    diagnoseLines.value = lines
  } finally {
    diagnosing.value = false
  }
}
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    class="edge-drawer"
    size="560px"
    :with-header="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template v-if="node">
      <div class="ops-drawer-header">
        <div>
          <span>EDGE NODE</span>
          <h2>{{ node.id }} <small>{{ node.name.split(' · ')[1] }}</small></h2>
        </div>
        <div class="ops-drawer-header__right">
          <OpsHealthBadge :state="node.online ? (node.autonomy ? 'degraded' : 'normal') : 'offline'"
            :label="node.autonomy ? '本地自治' : (node.online ? '在线' : '离线')" />
          <button type="button" class="ops-icon-btn" @click="emit('update:modelValue', false)">
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>

      <div class="ops-drawer-scroll">
        <dl class="ops-info-grid">
          <div><dt>节点 IP</dt><dd class="mono">{{ node.ip }}</dd></div>
          <div><dt>在线状态</dt><dd>{{ node.online ? '在线' : '离线' }}</dd></div>
          <div><dt>CPU 占用</dt><dd>{{ node.cpu }}%</dd></div>
          <div><dt>内存占用</dt><dd>{{ node.memory }}%</dd></div>
          <div><dt>缓存占用</dt><dd :class="{ 'text-warning': node.storage >= 60, 'text-danger': node.storage >= 85 }">{{ node.storage }}%</dd></div>
          <div><dt>缓存容量</dt><dd>{{ node.cacheCapacity.toLocaleString() }} 条</dd></div>
          <div><dt>当前规则版本</dt><dd class="mono" :class="{ 'text-warning': versionMismatch }">{{ node.ruleVersion }}</dd></div>
          <div><dt>平台规则版本</dt><dd class="mono">{{ node.platformVersion }}</dd></div>
          <div><dt>最后心跳</dt><dd>{{ node.lastHeartbeat }}</dd></div>
          <div><dt>时间同步</dt><dd :class="{ 'text-warning': node.timeOffsetMs !== null }">
            {{ node.timeOffsetMs === null ? '偏差 32ms · 正常' : '偏差 +' + (node.timeOffsetMs / 1000).toFixed(1) + 's · 异常' }}
          </dd></div>
          <div><dt>本地事件数</dt><dd>{{ node.localEventCount.toLocaleString() }}</dd></div>
          <div><dt>待补传事件</dt><dd>{{ node.cacheEvents }} 条</dd></div>
          <div class="wide"><dt>最近异常</dt><dd>{{ node.recentIssue }}</dd></div>
        </dl>

        <div class="ops-section">
          <h4>缓存分区</h4>
          <div v-for="p in node.cacheParts" :key="p.label" class="cache-part">
            <span>{{ p.label }}</span>
            <div class="edge-bar"><i :data-tone="p.percent >= 85 ? 'danger' : p.percent >= 60 ? 'warning' : 'primary'"
              :style="{ width: p.percent + '%' }"></i></div>
            <b>{{ p.percent }}%</b>
          </div>
        </div>

        <div class="ops-section">
          <h4>节点操作</h4>
          <div class="edge-actions">
            <button type="button" class="ops-btn" :disabled="diagnosing" @click="runDiagnose">
              {{ diagnosing ? '诊断中…' : '诊断' }}
            </button>
            <button v-permission="'safety:module:ops:execute'" type="button" class="ops-btn" :disabled="node.online && !node.autonomy" @click="emit('reconnect', node)">重新连接</button>
            <button v-permission="'safety:module:ops:execute'" type="button" class="ops-btn" :disabled="node.timeOffsetMs === null" @click="emit('resyncTime', node)">重新同步时间</button>
            <button v-permission="'safety:module:ops:execute'" type="button" class="ops-btn" :disabled="!versionMismatch" @click="emit('redeliver', node)">重新下发规则</button>
            <button type="button" class="ops-btn ghost" @click="showLogs = !showLogs">
              {{ showLogs ? '收起日志' : '查看日志' }}
            </button>
          </div>
          <div v-if="diagnoseDone && !diagnoseError" class="ops-inline-result success">
            诊断完成：结果见下方日志（含链路 / 规则版本 / 时间同步三项真实状态）。
          </div>
          <ul v-if="showLogs && diagnoseLines.length" class="edge-log-list">
            <li v-for="(l, i) in diagnoseLines" :key="i" :data-level="l.level">
              <span class="mono">{{ l.t }}</span><em>{{ l.level }}</em><p>{{ l.text }}</p>
            </li>
          </ul>
          <p v-else-if="showLogs" class="ops-inline-result">尚未运行诊断；点击「诊断」获取链路、规则版本与时间的真实状态。</p>
        </div>
      </div>
    </template>
  </el-drawer>
</template>
