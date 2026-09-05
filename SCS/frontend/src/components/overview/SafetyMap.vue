<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Close, LocationFilled } from '@element-plus/icons-vue'
import MockRiskButton from './MockRiskButton.vue'
import SafetyMapCanvas from '@/components/shared/SafetyMapCanvas.vue'
import type { EquipmentPoint, PersonnelPoint } from '@/types/overview'
import type { FenceRecord } from '@/types/fence'

const props = defineProps<{
  people: PersonnelPoint[]
  equipment: EquipmentPoint[]
  riskActive: boolean
  fences: FenceRecord[]
  liveOverlay?: boolean
}>()

defineEmits<{ toggleRisk: [] }>()

const selectedId = ref<string>()
const selectedPerson = computed(() => props.people.find((person) => person.id === selectedId.value))

watch(() => props.riskActive, (active) => {
  selectedId.value = active ? 'P-ZHAO' : undefined
})
</script>

<template>
  <article class="dashboard-card safety-map-card">
    <header class="dashboard-card__header">
      <div><span>LIVE SITE SITUATION</span><h2>站场安全态势</h2>
        <p>人员、设备与电子围栏运行概览{{ liveOverlay ? '（风险状态来自实时告警）' : '' }}</p>
      </div>
      <div class="map-header-actions">
        <div class="map-legend"><span><i></i>正常</span><span><i></i>异常</span><span><i></i>高风险</span></div>
        <MockRiskButton :active="riskActive" @toggle="$emit('toggleRisk')" />
      </div>
    </header>

    <div class="safety-map" @click="selectedId = undefined">
      <SafetyMapCanvas
        :people="people"
        :equipment="equipment"
        :fences="fences"
        :selected-person-id="selectedId"
        @select-person="selectedId = $event.id"
      />

      <Transition name="map-popup">
        <div
          v-if="selectedPerson"
          class="person-popup"
          :class="{ 'is-danger': selectedPerson.state === 'danger' }"
          :style="{ left: `${Math.min(selectedPerson.x + 3, 68)}%`, top: `${Math.max(selectedPerson.y - 8, 7)}%` }"
          @click.stop
        >
          <button type="button" aria-label="关闭人员信息" @click="selectedId = undefined"><el-icon><Close /></el-icon></button>
          <div class="person-popup__title"><span><el-icon><LocationFilled /></el-icon></span><div><b>{{ selectedPerson.name }}</b><small>{{ selectedPerson.team }}</small></div></div>
          <dl>
            <div><dt>状态</dt><dd><i></i>{{ selectedPerson.status }}</dd></div>
            <div><dt>手环电量</dt><dd>{{ selectedPerson.battery }}%</dd></div>
            <div><dt>当前位置</dt><dd>{{ selectedPerson.area }}</dd></div>
            <div><dt>风险状态</dt><dd :data-risk="selectedPerson.state">{{ selectedPerson.risk }}</dd></div>
          </dl>
        </div>
      </Transition>

    </div>
  </article>
</template>
