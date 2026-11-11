<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import BusinessModulePanel from '../BusinessModulePanel.vue'
import { BUSINESS_MODULES } from '../modules'
import snapshot from './catalog-snapshot.json'
import type { CatalogDevice } from './types'

const module = BUSINESS_MODULES.scs
const devices = snapshot.devices as CatalogDevice[]

// ---------- 消费链路状态（SCS 后端登记代理后可用；此前手动填地址尝试，失败不影响页面） ----------
const scsBaseUrl = ref(localStorage.getItem('scs-public-status-base') ?? '')
const statusLoading = ref(false)
const statusError = ref('')
const status = ref<Record<string, unknown> | null>(null)

async function loadStatus() {
  if (!scsBaseUrl.value.trim()) {
    ElMessage.warning('先填写 SCS 后端地址（含 /api/v1 前缀的管理端口）')
    return
  }
  localStorage.setItem(localStorageKey, scsBaseUrl.value.trim())
  statusLoading.value = true
  statusError.value = ''
  status.value = null
  try {
    const resp = await fetch(scsBaseUrl.value.trim().replace(/\/$/, '') + '/public-data/status')
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    status.value = await resp.json()
  } catch (e) {
    statusError.value = '无法读取状态（跨域/网络/权限）——SCS 后端代理登记前属预期；' + String(e).slice(0, 80)
  } finally {
    statusLoading.value = false
  }
}

const localStorageKey = 'scs-public-status-base'

// ---------- 数据生成申请单（底座开关在董博涵侧；本页产出结构化申请单） ----------
const form = reactive({ devices: [] as string[], scenario: '', durationMin: 10, remark: '' })
const selectedDevices = computed(() => devices.filter((d) => form.devices.includes(d.device_id)))
const scenarioOptions = computed(() => {
  const set = new Set<string>()
  selectedDevices.value.forEach((d) => d.scenarios.forEach((s) => set.add(s)))
  return Array.from(set)
})
const requests = ref<Array<Record<string, unknown>>>(JSON.parse(localStorage.getItem('scs-generation-requests') ?? '[]'))

function buildRequest(): Record<string, unknown> {
  return {
    申请时间: new Date().toLocaleString('zh-CN'),
    申请系统: 'SCS · 安全管控',
    申请设备: selectedDevices.value.map((d) => `${d.name}（${d.device_id} → SCS ${d.scs_domain}/${d.scs_object_code}）`),
    生成场景: form.scenario || '未选择',
    建议时长_分钟: form.durationMin,
    备注: form.remark || '无',
    交付约定: '由董博涵在公共底座侧显式启动生成；SCS 经 MQ b-device-committed-v1 组 b-device-committed-scs-v1 接收',
  }
}

function submitRequest() {
  if (!form.devices.length) {
    ElMessage.warning('至少选择一台设备')
    return
  }
  const request = buildRequest()
  requests.value = [request, ...requests.value].slice(0, 20)
  localStorage.setItem('scs-generation-requests', JSON.stringify(requests.value))
  ElMessage.success('申请单已生成——复制后发给董博涵协调启动')
}

async function copyLatest() {
  if (!requests.value.length) {
    ElMessage.warning('还没有申请单')
    return
  }
  const text = JSON.stringify(requests.value[0], null, 2)
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('申请单已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}

function clearRequests() {
  requests.value = []
  localStorage.removeItem('scs-generation-requests')
  ElMessage.success('本地申请历史已清空')
}
</script>

<template>
  <BusinessModulePanel :module="module">
    <div class="scs-slot">
    <el-alert type="info" :closable="false" show-icon
      title="数据生成开关默认关闭（自愿原则）"
      description="公共底座由董博涵在平台侧显式启动生成；本页用于核对接收绑定、产出结构化生成申请单、并在登记代理后查看 SCS 接收链路状态。" />

    <h3 class="scs-section">接收链路状态（SCS 后端）</h3>
    <div class="scs-status-row">
      <el-input v-model="scsBaseUrl" placeholder="SCS 后端地址，如 http://192.168.101.57:18082/api/v1" clearable />
      <el-button type="primary" :loading="statusLoading" @click="loadStatus">读取状态</el-button>
    </div>
    <el-alert v-if="statusError" :title="statusError" type="warning" :closable="false" show-icon class="scs-gap" />
    <el-descriptions v-else-if="status" :column="3" border class="scs-gap" size="small">
      <el-descriptions-item label="接收箱 RECEIVED">{{ status.received }}</el-descriptions-item>
      <el-descriptions-item label="已处理 PROCESSED">{{ status.processed }}</el-descriptions-item>
      <el-descriptions-item label="失败 FAILED">{{ status.failed }}</el-descriptions-item>
      <el-descriptions-item label="补读游标">{{ status.cursor || '—' }}</el-descriptions-item>
      <el-descriptions-item label="消费组">{{ status.group }}</el-descriptions-item>
      <el-descriptions-item label="Topic">{{ status.topic }}</el-descriptions-item>
    </el-descriptions>
    <p v-else class="muted scs-gap">SCS 后端接口登记代理后，此处自动展示收件箱计数、补读游标与消费组信息。</p>

    <h3 class="scs-section">设备绑定覆盖（交付快照 {{ snapshot.devices.length }} 台 / S3 字典 {{ snapshot.s3_fields.length }} 字段）</h3>
    <el-table :data="devices" size="small" border>
      <el-table-column prop="name" label="模拟设备" min-width="130" />
      <el-table-column prop="device_id" label="设备 ID" min-width="200" />
      <el-table-column prop="template_id" label="模板" min-width="140" />
      <el-table-column prop="scs_domain" label="SCS 业务域" width="100" />
      <el-table-column prop="scs_object_code" label="SCS 对象码" width="140" />
    </el-table>
    <p class="muted scs-gap">未知设备/点码按 SCS ingest 原则 ignored，不自动建档；S3 字段逐点映射核对状态见交接说明 §八。</p>

    <h3 class="scs-section">数据生成申请单（交互）</h3>
    <el-form label-width="90px">
      <el-form-item label="目标设备">
        <el-select v-model="form.devices" multiple placeholder="选择需要生成的设备" style="width: 100%">
          <el-option v-for="d in devices" :key="d.device_id" :label="d.name" :value="d.device_id" />
        </el-select>
      </el-form-item>
      <el-form-item label="生成场景">
        <el-select v-model="form.scenario" placeholder="先选设备再选场景" style="width: 100%"
          :disabled="!form.devices.length">
          <el-option v-for="s in scenarioOptions" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="建议时长">
        <el-input-number v-model="form.durationMin" :min="1" :max="240" /> 分钟
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="演示时段、关注的观测点、质量要求等" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="submitRequest">生成申请单</el-button>
        <el-button @click="copyLatest">复制最新申请单</el-button>
        <el-button @click="clearRequests">清空本地历史</el-button>
      </el-form-item>
    </el-form>
    <el-collapse v-if="requests.length">
      <el-collapse-item :title="'本地申请历史（' + requests.length + ' 条，存于浏览器）'">
        <pre class="scs-request">{{ JSON.stringify(requests[0], null, 2) }}</pre>
      </el-collapse-item>
    </el-collapse>
  </div>
  </BusinessModulePanel>
</template>

<style scoped>
.scs-slot { display: block; }
.scs-section { margin: 18px 0 10px; font-size: 15px; }
.scs-gap { margin-top: 10px; }
.scs-status-row { display: flex; gap: 8px; }
.scs-request { background: #f5f7fa; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; }
</style>
