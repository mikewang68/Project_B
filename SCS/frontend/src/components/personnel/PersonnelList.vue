<script setup lang="ts">
import { Location } from '@element-plus/icons-vue'
import StatusBadge from '@/components/shared/StatusBadge.vue'
import type { PersonnelRecord } from '@/types/personnel'

defineProps<{ people: PersonnelRecord[]; selectedId: string }>()
defineEmits<{ select: [person: PersonnelRecord] }>()
</script>

<template>
  <aside class="personnel-list-card dashboard-card">
    <header><div><span>PERSONNEL</span><h2>现场人员</h2></div><b>{{ people.length }} 人</b></header>
    <div class="personnel-list">
      <button
        v-for="person in people" :key="person.id" type="button"
        class="person-card" :class="{ 'is-selected': person.id === selectedId }"
        @click="$emit('select', person)"
      >
        <span class="person-card__avatar" :data-state="person.state">{{ person.name.slice(0, 1) }}</span>
        <span class="person-card__content">
          <span><b>{{ person.name }}</b><StatusBadge :status="person.risk" /></span>
          <small>{{ person.team }} · {{ person.jobNo }}</small>
          <em><el-icon><Location /></el-icon>{{ person.area }}</em>
          <i>{{ person.braceletStatus }} · 电量 {{ person.battery }}% · 定位{{ person.positioningQuality }}</i>
        </span>
      </button>
      <div v-if="!people.length" class="list-empty">没有符合当前筛选条件的人员</div>
    </div>
  </aside>
</template>
