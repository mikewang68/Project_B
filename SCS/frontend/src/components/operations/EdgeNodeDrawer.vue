<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import type { EdgeNode } from '@/types/operations'
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

watch(() => props.modelValue, (v) => {
  if (v) { showLogs.value = false; diagnoseDone.value = false }
})

const versionMismatch = computed(() => !!props.node && props.node.ruleVersion !== props.node.platformVersion)

const mockLogs = computed(() => {
  if (!props.node) return []
  return [
    { t: '11:42:18', level: 'INFO', text: `${props.node.id} 本地规则引擎心跳正常` },
    { t: '11:41:55', level: 'INFO', text: `规则版本 ${props.node.ruleVersion} 校验通过` },
    { t: '11:40:12', level: 'WARN', text: `缓存占用 ${props.node.storage}%，高等级事件优先保留` },
    { t: '11:38:40', level: 'INFO', text: '感知设备轮询完成，全部设备可达' },
    { t: '11:35:09', level: 'INFO', text: '本地联动策略自检通过' },
  ]
})

function runDiagnose(): void {
  diagnosing.value = true
  diagnoseDone.value = false
  window.setTimeout(() => { diagnosing.value = false; diagnoseDone.value = true }, 1100)
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
            <button type="button" class="ops-btn" :disabled="node.online && !node.autonomy" @click="emit('reconnect', node)">重新连接</button>
            <button type="button" class="ops-btn" :disabled="node.timeOffsetMs === null" @click="emit('resyncTime', node)">重新同步时间</button>
            <button type="button" class="ops-btn" :disabled="!versionMismatch" @click="emit('redeliver', node)">重新下发规则</button>
            <button type="button" class="ops-btn ghost" @click="showLogs = !showLogs">
              {{ showLogs ? '收起日志' : '查看日志' }}
            </button>
          </div>
          <div v-if="diagnoseDone" class="ops-inline-result success">
            诊断完成：规则引擎、感知总线、本地联动、缓存读写均正常。
          </div>
          <ul v-if="showLogs" class="edge-log-list">
            <li v-for="(l, i) in mockLogs" :key="i" :data-level="l.level">
              <span class="mono">{{ l.t }}</span><em>{{ l.level }}</em><p>{{ l.text }}</p>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </el-drawer>
</template>
