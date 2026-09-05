<script setup lang="ts">
import { ref } from 'vue'
import type { DeviceCategory, OpsDevice } from '@/types/operations'
import { onlineCount } from '@/mock/opsData'
import OpsHealthBadge from './OpsHealthBadge.vue'

defineProps<{ categories: DeviceCategory[] }>()
const emit = defineEmits<{ reconnect: [device: OpsDevice] }>()

const selected = ref<DeviceCategory | null>(null)
function toggle(c: DeviceCategory): void {
  selected.value = selected.value?.kind === c.kind ? null : c
}
const abnormalOf = (c: DeviceCategory) => c.devices.filter((d) => d.state !== 'normal')
</script>

<template>
  <div class="dashboard-card ops-panel">
    <div class="ops-card-head">
      <div><span>DEVICE HEALTH</span><h3>感知设备健康</h3></div>
      <small>点击分类查看异常设备</small>
    </div>
    <div class="device-kind-grid">
      <button v-for="c in categories" :key="c.kind" type="button" class="device-kind"
        :class="{ active: selected?.kind === c.kind }"
        :data-abnormal="onlineCount(c.devices) < c.total"
        @click="toggle(c)">
        <b>{{ c.kind }}</b>
        <span>{{ onlineCount(c.devices) }} / {{ c.total }} 在线</span>
        <i v-if="abnormalOf(c).length" class="device-kind__warn">{{ abnormalOf(c).length }} 台异常</i>
      </button>
    </div>

    <div v-if="selected" class="device-abnormal">
      <div class="device-abnormal__head">
        <b>{{ selected.kind }} · 异常 / 离线设备</b>
        <small>共 {{ abnormalOf(selected).length }} 台</small>
      </div>
      <p v-if="!abnormalOf(selected).length" class="device-abnormal__empty">该分类设备全部正常</p>
      <div v-for="d in abnormalOf(selected)" :key="d.id" class="device-row">
        <div>
          <b class="mono">{{ d.id }}</b>
          <small>{{ d.name }} · 挂载 {{ d.edgeId }}</small>
        </div>
        <OpsHealthBadge :state="d.state" :label="d.state === 'offline' ? '离线' : d.issue" />
        <button type="button" class="ops-btn small" @click="emit('reconnect', d)">重新连接</button>
      </div>
    </div>
  </div>
</template>
