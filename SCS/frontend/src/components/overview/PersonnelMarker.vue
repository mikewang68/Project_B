<script setup lang="ts">
import { UserFilled } from '@element-plus/icons-vue'
import type { PersonnelPoint } from '@/types/overview'

defineProps<{ person: PersonnelPoint; selected: boolean }>()
defineEmits<{ select: [person: PersonnelPoint] }>()
</script>

<template>
  <button
    class="personnel-marker"
    :class="{ 'is-selected': selected }"
    :data-state="person.state"
    :style="{ left: `${person.x}%`, top: `${person.y}%` }"
    type="button"
    :aria-label="`查看${person.name}信息`"
    @click.stop="$emit('select', person)"
  >
    <span class="personnel-marker__core"><el-icon><UserFilled /></el-icon></span>
    <i></i>
    <span class="marker-tooltip"><b>{{ person.name }}</b><small>{{ person.area }} · {{ person.risk }}</small></span>
  </button>
</template>
