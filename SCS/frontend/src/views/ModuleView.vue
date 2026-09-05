<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import LevelTag from '@/components/LevelTag.vue'
import SiteMap from '@/components/SiteMap.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useSafetyStore } from '@/stores/safety'

const props = defineProps<{ module: string }>()
const store = useSafetyStore()
const search = ref('')
const alarmLevel = ref('全部')
const snapshot = computed(() => store.snapshot)
const people = computed(() => snapshot.value.people.filter((person) => `${person.name}${person.team}${person.bracelet}`.includes(search.value)))
const alarms = computed(() => snapshot.value.alarms.filter((alarm) => (alarmLevel.value === '全部' || alarm.level === alarmLevel.value) && `${alarm.id}${alarm.title}${alarm.area}${alarm.objectName}`.includes(search.value)))

const duration = (seconds: number) => seconds < 60 ? `${seconds}秒` : `${Math.floor(seconds / 60)}分${seconds % 60}秒`

async function safeAction(action: () => Promise<void>, success: string): Promise<void> {
  if (store.usingFallback) return void ElMessage.info('当前为前端演示快照；启动后端后可执行该操作。')
  await action()
  ElMessage.success(success)
}
</script>

<template>
  <section class="page-content module-page">
    <template v-if="props.module === 'people'">
      <div class="toolbar"><el-input v-model="search" clearable placeholder="搜索姓名、班组或手环编号" /><el-button>导出轨迹</el-button></div>
      <div class="people-layout"><article class="panel"><header><div><span>REAL-TIME LOCATION</span><h2>人员实时定位</h2></div><b>{{ people.length }} 人在线</b></header><SiteMap :people="people" :fences="snapshot.fences" :devices="snapshot.devices" /></article>
      <article class="panel directory"><header><div><span>PERSONNEL</span><h2>人员与手环状态</h2></div></header><div v-for="person in people" :key="person.id" class="person-row"><div class="avatar small">{{ person.name[0] }}</div><span><b>{{ person.name }} · {{ person.role }}</b><small>{{ person.team }} / {{ person.bracelet }}</small></span><em :data-state="person.status">{{ person.status === 'normal' ? '正常' : person.status === 'danger' ? '危险' : '预警' }}</em><strong>{{ person.battery }}%</strong></div></article></div>
    </template>

    <template v-else-if="props.module === 'fences'">
      <div class="toolbar"><div><b>规则生命周期</b><small>配置、评审、发布与边缘回执统一管理</small></div><el-button>版本对账</el-button><el-button type="primary">新建临时围栏</el-button></div>
      <div class="card-grid"><article v-for="fence in snapshot.fences" :key="fence.id" class="entity-card"><div class="fence-preview"><div class="fence-shape" :data-level="fence.level"></div><span>{{ fence.type }}</span></div><div class="entity-body"><div><LevelTag :level="fence.level" /><el-tag :type="fence.edgeSynced ? 'success' : 'warning'" size="small">{{ fence.edgeSynced ? '边缘已同步' : '待同步' }}</el-tag></div><h3>{{ fence.name }}</h3><p>{{ fence.id }} · {{ fence.version }} · {{ fence.appliesTo }}</p><el-switch :model-value="fence.enabled" inline-prompt active-text="启" inactive-text="停" @change="safeAction(() => store.setFenceEnabled(fence.id, !fence.enabled), '围栏状态已更新')" /></div></article></div>
    </template>

    <template v-else-if="props.module === 'devices'">
      <div class="mini-stats"><span><small>接入设备</small><b>{{ snapshot.devices.length }}</b>台</span><span><small>风险设备</small><b>{{ store.riskyDevices.length }}</b>项</span><span><small>平均雷达质量</small><b>{{ Math.round(snapshot.devices.reduce((sum, item) => sum + item.sensorQuality, 0) / snapshot.devices.length) }}</b>%</span></div>
      <div class="card-grid devices"><article v-for="device in snapshot.devices" :key="device.id" class="entity-card device-card" :data-state="device.status"><div class="device-head"><span><i></i>{{ device.type }}</span><em>{{ device.controlState }}</em></div><h3>{{ device.name }}</h3><div class="distance"><strong>{{ device.distance.toFixed(1) }}</strong><span>m<br />当前距离</span></div><div class="device-data"><span>阈值<b>{{ device.threshold }}m</b></span><span>速度<b>{{ device.speed }}km/h</b></span><span>雷达质量<b>{{ device.sensorQuality }}%</b></span><span>关联人员<b>{{ device.linkedPerson }}</b></span></div></article></div>
    </template>

    <template v-else-if="props.module === 'ai'">
      <div class="toolbar"><div><b>AI 结论必须人工复核</b><small>低置信度事件不直接作为高危处置依据</small></div><el-tag type="warning">{{ store.pendingAi.length }} 条待复核</el-tag></div>
      <div class="card-grid ai"><article v-for="event in snapshot.aiEvents" :key="event.id" class="entity-card ai-card"><div class="ai-visual"><span>{{ event.camera }}</span><i></i><b>演示证据画面</b></div><div class="entity-body"><el-tag :type="event.status === 'pending' ? 'warning' : event.status === 'confirmed' ? 'danger' : 'info'">{{ event.status === 'pending' ? '待复核' : event.status === 'confirmed' ? '已确认' : '已处理' }}</el-tag><h3>{{ event.type }}</h3><p>{{ event.area }} · 置信度 {{ Math.round(event.confidence * 100) }}% · {{ event.modelVersion }}</p><div class="card-actions"><el-button @click="safeAction(() => store.reviewAi(event.id, 'confirmed'), '已确认成立')">确认成立</el-button><el-button @click="safeAction(() => store.reviewAi(event.id, 'false_positive'), '已标记误报')">标记误报</el-button></div></div></article></div>
    </template>

    <template v-else-if="props.module === 'alarms'">
      <div class="toolbar"><el-segmented v-model="alarmLevel" :options="['全部', '紧急', '严重', '一般']" /><el-input v-model="search" clearable placeholder="搜索编号、区域或对象" /><el-button>导出明细</el-button></div>
      <article class="panel table-panel"><el-table :data="alarms" row-key="id"><el-table-column prop="id" label="告警编号" min-width="175" /><el-table-column label="告警内容" min-width="270"><template #default="{ row }"><b>{{ row.title }}</b><small class="cell-sub">{{ row.objectName }} · trace {{ row.traceId }}</small></template></el-table-column><el-table-column label="等级" width="90"><template #default="{ row }"><LevelTag :level="row.level" /></template></el-table-column><el-table-column prop="source" label="来源" width="120" /><el-table-column prop="area" label="区域" min-width="150" /><el-table-column label="持续" width="90"><template #default="{ row }">{{ duration(row.durationSeconds) }}</template></el-table-column><el-table-column label="状态 / 责任人" width="140"><template #default="{ row }"><b>{{ row.status }}</b><small class="cell-sub">{{ row.owner }}</small></template></el-table-column><el-table-column label="操作" width="170" fixed="right"><template #default="{ row }"><el-button size="small" @click="safeAction(() => store.actOnAlarm(row.id, 'accept'), '告警已接单')">接单</el-button><el-button size="small" type="primary" plain @click="safeAction(() => store.actOnAlarm(row.id, 'close'), '告警已关闭')">关闭</el-button></template></el-table-column></el-table></article>
    </template>

    <template v-else-if="props.module === 'analytics'">
      <div class="mini-stats"><span><small>近 30 日事件</small><b>286</b>起</span><span><small>闭环率</small><b>96.8</b>%</span><span><small>平均响应</small><b>2.4</b>分钟</span><span><small>高风险区域</small><b>3</b>处</span></div>
      <div class="analytics-layout"><article class="panel"><header><div><span>30 DAY TREND</span><h2>安全事件趋势</h2></div></header><TrendChart /></article><article class="panel ranking"><header><div><span>RISK RANKING</span><h2>高频风险区域</h2></div></header><div v-for="(item, index) in [['龙门吊运行区', 86], ['翻箱机南侧', 71], ['装卸作业线 A', 58], ['原料装卸区', 42]]" :key="item[0]" class="rank-row"><b>{{ index + 1 }}</b><span>{{ item[0] }}</span><i><em :style="{ width: `${item[1]}%` }"></em></i><strong>{{ item[1] }}</strong></div></article></div>
    </template>

    <template v-else-if="props.module === 'rules'">
      <div class="toolbar"><div><b>规则发布采用职责分离</b><small>编辑、审批、发布与边缘回执均进入审计链</small></div><el-button type="primary">新建规则草稿</el-button></div>
      <article class="panel rule-list"><div v-for="rule in snapshot.rules" :key="rule.id" class="rule-row"><span class="rule-domain">{{ rule.domain }}</span><div><b>{{ rule.name }}</b><small>{{ rule.id }} · {{ rule.version }}</small></div><LevelTag :level="rule.level" /><el-tag :type="rule.edgeSynced ? 'success' : 'warning'">{{ rule.edgeSynced ? '边缘已同步' : '待同步' }}</el-tag><el-switch :model-value="rule.enabled" @change="safeAction(() => store.setRuleEnabled(rule.id, !rule.enabled), '规则状态已更新')" /></div></article>
      <article class="panel audit-panel"><header><div><span>CHANGE AUDIT</span><h2>最近变更审计</h2></div></header><div v-for="log in snapshot.auditLogs" :key="log.id" class="audit-row"><span>{{ new Date(log.time).toLocaleTimeString('zh-CN', { hour12: false }) }}</span><b>{{ log.action }}</b><small>{{ log.operator }}</small><em>{{ log.result }}</em><code>{{ log.traceId }}</code></div></article>
    </template>

    <template v-else-if="props.module === 'operations'">
      <div class="network-banner" :class="{ offline: !snapshot.system.cloudConnected }"><div><i></i><span><b>{{ snapshot.system.cloudConnected ? '云边连接正常' : '云边网络断开，边缘自治运行' }}</b><small>{{ snapshot.system.cloudConnected ? '规则、事件和审计日志实时同步' : `${snapshot.system.cachedEvents} 条事件等待补传` }}</small></span></div><el-button @click="safeAction(() => store.setNetwork(!snapshot.system.cloudConnected), snapshot.system.cloudConnected ? '已进入断网自治' : '网络已恢复')">{{ snapshot.system.cloudConnected ? '模拟断网' : '恢复网络' }}</el-button></div>
      <div class="ops-layout"><article class="panel topology"><header><div><span>EDGE TOPOLOGY</span><h2>云边端协同拓扑</h2></div></header><div class="topology-body"><div class="node cloud">S0 云平台<small>应用 / 事件 / 统计</small></div><i></i><div class="node edge">边缘网关<small>规则引擎 · 断网自治</small></div><i></i><div class="sensor-row"><span>定位系统<b>8 手环</b></span><span>雷达设备<b>4 设备</b></span><span>AI 摄像机<b>12 点位</b></span></div></div></article><article class="panel"><header><div><span>SERVICE MONITOR</span><h2>服务运行状态</h2></div><b>{{ snapshot.system.availability }}%</b></header><div v-for="service in snapshot.system.services" :key="service.name" class="service-row large"><i :data-state="service.state"></i><span><b>{{ service.name }}</b><small>{{ service.detail }}</small></span><em>{{ service.latencyMs }}ms</em></div></article></div>
      <div class="mini-stats"><span><small>待补传事件</small><b>{{ snapshot.system.cachedEvents }}</b>条</span><span><small>本地规则版本</small><b>07.22.3</b></span><span><small>时钟偏差</small><b>12</b>ms</span><span><small>缓存使用率</small><b>{{ Math.min(98, 2 + snapshot.system.cachedEvents) }}</b>%</span></div>
    </template>
  </section>
</template>

