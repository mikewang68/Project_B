<script setup lang="ts">
import { computed } from 'vue'
import { Bell, Connection, Timer, User, Warning, WarningFilled } from '@element-plus/icons-vue'
import type { MobileIncident } from '@/types/incident'
import type { ScreenOverview } from '@/api/screen'

const props = defineProps<{ incidents: MobileIncident[]; overview?: ScreenOverview | null }>()

const metrics = computed(() => {
  const ov = props.overview
  // 告警口径来自后端聚合；无数据时（加载中）以 0 兜底
  const todayAlerts = ov?.todayAlerts ?? props.incidents.length
  const handling = ov?.processingAlerts ?? props.incidents.filter((i) => ['已接单', '已到场', '处理中'].includes(i.status)).length
  const high = ov ? ov.urgentAlerts + ov.severeAlerts : props.incidents.filter((i) => (i.risk === '严重' || i.risk === '紧急') && i.status !== '已关闭').length
  const deviceOnline = ov?.deviceOnline ?? 36
  const deviceTotal = ov?.deviceTotal ?? 38
  const onDuty = ov?.onDuty ?? 128
  return [
    { key: 'staff', label: '在岗人员', value: String(onDuty), unit: '人', sub: ov?.onDutyDemo ? 'Demo 台账 · 当班计划 126 人' : '当班计划 126 人', icon: User, tone: 'blue' },
    { key: 'device', label: '在线设备', value: `${deviceOnline} / ${deviceTotal}`, unit: '台', sub: ov?.deviceDemo ? 'Demo 台账 · 在线率 94.7%' : '设备在线率 94.7%', icon: Connection, tone: 'green' },
    { key: 'alert', label: '今日告警', value: String(todayAlerts), unit: '条', sub: '来自告警中心实时统计', icon: Bell, tone: 'blue' },
    { key: 'handling', label: '处理中', value: String(handling), unit: '起', sub: '全部已派发责任人', icon: Timer, tone: 'amber' },
    { key: 'high', label: '高风险事件', value: String(high), unit: '起', sub: high > 0 ? '严重 + 紧急，需要重点关注' : '当前状态平稳', icon: WarningFilled, tone: high > 0 ? 'red' : 'green' },
    { key: 'rate', label: '闭环率', value: '93.4', unit: '%', sub: 'Demo 统计 · 平均关闭 16m42s', icon: Warning, tone: 'blue' },
  ]
})
</script>

<template>
  <section class="bs-metrics">
    <div v-for="m in metrics" :key="m.key" class="bs-metric" :data-tone="m.tone">
      <div class="bs-metric__icon"><el-icon :size="20"><component :is="m.icon" /></el-icon></div>
      <div class="bs-metric__body">
        <span class="bs-metric__label">{{ m.label }}</span>
        <b>{{ m.value }}<i>{{ m.unit }}</i></b>
        <small>{{ m.sub }}</small>
      </div>
    </div>
  </section>
</template>
