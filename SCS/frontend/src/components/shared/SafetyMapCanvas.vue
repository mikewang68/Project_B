<script setup lang="ts">
import EquipmentMarker from '@/components/overview/EquipmentMarker.vue'
import PersonnelMarker from '@/components/overview/PersonnelMarker.vue'
import FencePolygon from './FencePolygon.vue'
import type { EquipmentPoint, PersonnelPoint } from '@/types/overview'
import type { FencePoint, FenceRecord } from '@/types/fence'
import type { TrackPoint } from '@/types/personnel'

const props = withDefaults(defineProps<{
  people?: PersonnelPoint[]
  equipment?: EquipmentPoint[]
  fences?: FenceRecord[]
  selectedPersonId?: string | undefined
  selectedFenceId?: string | undefined
  editPoints?: FencePoint[]
  track?: TrackPoint[]
  trackIndex?: number
  editing?: boolean
  showEquipment?: boolean
}>(), {
  people: () => [], equipment: () => [], fences: () => [], selectedPersonId: '',
  selectedFenceId: '', editPoints: () => [], track: () => [], trackIndex: 0,
  editing: false, showEquipment: true,
})

const emit = defineEmits<{
  selectPerson: [person: PersonnelPoint]
  selectFence: [fence: FenceRecord]
  mapPoint: [point: FencePoint]
}>()

function addPoint(event: MouseEvent): void {
  if (!props.editing) return
  const target = event.currentTarget as HTMLElement
  const bounds = target.getBoundingClientRect()
  emit('mapPoint', {
    x: Math.max(1, Math.min(99, ((event.clientX - bounds.left) / bounds.width) * 100)),
    y: Math.max(1, Math.min(99, ((event.clientY - bounds.top) / bounds.height) * 100)),
  })
}
</script>

<template>
  <div class="shared-map-canvas" :class="{ 'is-editing': editing }" @click="addPoint">
    <div class="safety-map__grid"></div>
    <svg class="vehicle-lanes" viewBox="0 0 1000 560" preserveAspectRatio="none" aria-hidden="true">
      <path d="M40 294 H365 C430 294 430 245 496 245 H950" />
      <path d="M502 48 V510" />
      <path class="lane-center" d="M40 294 H365 C430 294 430 245 496 245 H950" />
      <path class="lane-center" d="M502 48 V510" />
    </svg>

    <div class="map-zone zone-loading-a"><span>A</span><b>装卸区 A</b><small>人员作业区</small></div>
    <div class="map-zone zone-loading-b"><span>B</span><b>装卸区 B</b><small>车辆装卸区</small></div>
    <div class="map-zone zone-crane"><span>01</span><b>龙门吊作业区</b><small>高危设备作业</small></div>
    <div class="map-zone zone-tipper"><span>02</span><b>翻箱机作业区</b><small>设备联动监测</small></div>
    <div class="map-zone zone-construction"><span>!</span><b>临时施工区</b><small>限制人员进入</small></div>
    <div class="map-lane-label"><i></i>车辆通道</div>

    <svg class="map-vector-layer" viewBox="0 0 100 100" preserveAspectRatio="none" aria-label="站场电子围栏">
      <FencePolygon
        v-for="fence in fences"
        :key="fence.id"
        :points="fence.polygon"
        :tone="fence.tone"
        :selected="selectedFenceId === fence.id"
        :label="fence.name"
        @click.stop="emit('selectFence', fence)"
      />
      <polyline v-if="track.length > 1" class="track-path" :points="track.map((point) => `${point.x},${point.y}`).join(' ')" />
      <circle v-for="(point, index) in track" :key="`track-${index}`" class="track-node" :class="{ 'is-passed': index <= trackIndex }" :cx="point.x" :cy="point.y" r="0.55" />
      <FencePolygon v-if="editPoints.length" :points="editPoints" tone="temporary" preview label="新围栏预览" />
    </svg>

    <PersonnelMarker
      v-for="person in people"
      :key="person.id"
      :person="person"
      :selected="selectedPersonId === person.id"
      @select="emit('selectPerson', $event)"
    />
    <template v-if="showEquipment">
      <EquipmentMarker v-for="item in equipment" :key="item.id" :equipment="item" />
    </template>

    <div v-if="editing" class="map-edit-hint">点击地图添加边界点 · 已添加 {{ editPoints.length }} 个点</div>
    <div class="map-meta"><span><i></i>实时态势</span><b>人员 {{ people.length }} · 围栏 {{ fences.length }}</b></div>
  </div>
</template>
