<script setup lang="ts">
import { ref } from 'vue'
import { ElDrawer } from 'element-plus'
import { Close, Top, Bottom, Minus } from '@element-plus/icons-vue'
import RiskBadge from '@/components/shared/RiskBadge.vue'
import type { HotDevice } from '@/types/analytics'

defineProps<{ items: HotDevice[] }>()
const emit = defineEmits<{ drill: [deviceId: string, label: string] }>()
const current = ref<HotDevice>()
const drawerOpen = ref(false)

function open(d: HotDevice): void {
  current.value = d
  drawerOpen.value = true
  emit('drill', d.deviceId, d.name)
}
</script>

<template>
  <div class="chart-card hot-device">
    <header>
      <div><span>HOTSPOT EQUIPMENT</span><h3>高频风险设备</h3></div>
      <small>本月 · 点击查看</small>
    </header>
    <ul class="hot-device__list">
      <li v-for="d in items" :key="d.deviceId" @click="open(d)">
        <div class="hot-device__id"><b>{{ d.name }}</b><small>{{ d.primaryRisk }} {{ d.primaryCount }} 次</small></div>
        <span class="hot-device__count">{{ d.count }}<small>起</small></span>
        <span class="hot-device__trend" :data-trend="d.trend">
          <el-icon v-if="d.trend==='up'"><Top /></el-icon>
          <el-icon v-else-if="d.trend==='down'"><Bottom /></el-icon>
          <el-icon v-else><Minus /></el-icon>
        </span>
      </li>
    </ul>

    <ElDrawer :model-value="drawerOpen" size="460px" :with-header="false" class="device-mini-drawer"
      @update:model-value="drawerOpen = $event">
      <template v-if="current">
        <header class="device-mini-header">
          <div><span>EQUIPMENT PROFILE</span><h2>{{ current.name }}</h2><p>{{ current.deviceId }} · 风险画像（Mock）</p></div>
          <button type="button" aria-label="关闭" @click="drawerOpen = false"><el-icon><Close /></el-icon></button>
        </header>
        <div class="device-mini-scroll">
          <div class="device-mini-kpis">
            <div><small>本月事件</small><b>{{ current.count }} 起</b></div>
            <div><small>主要风险</small><b>{{ current.primaryRisk }}</b></div>
          </div>
          <p class="device-mini-recent">最近风险：{{ current.recentRisk }}</p>
          <section class="device-mini-block">
            <h4>风险类型分布</h4>
            <div v-for="s in current.typeSplit" :key="s.type" class="device-mini-type">
              <span>{{ s.type }}</span>
              <span class="device-mini-typebar"><i :style="{ width: (s.count / current.count * 100) + '%' }"></i></span>
              <b>{{ s.count }}</b>
            </div>
          </section>
          <section class="device-mini-block">
            <h4>最近 5 条事件</h4>
            <ul class="device-mini-events">
              <li v-for="(e, i) in current.recentEvents" :key="i">
                <time>{{ e.time }}</time><span>{{ e.type }}</span><RiskBadge :risk="e.level" />
              </li>
            </ul>
          </section>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>
