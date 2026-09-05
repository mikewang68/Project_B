<script setup lang="ts">
import { ref } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import type { SafetyRule } from '@/types/rule'

const props = defineProps<{ rule: SafetyRule }>()
const openVersion = ref<string>(props.rule.versions[0]?.version ?? '')
function toggle(v: string): void {
  openVersion.value = openVersion.value === v ? '' : v
}
</script>

<template>
  <section class="version-history">
    <h4>版本历史</h4>
    <ul class="version-history__list">
      <li v-for="v in rule.versions" :key="v.version" :data-current="v.state === '当前'">
        <button type="button" class="version-history__row" @click="toggle(v.version)">
          <span class="version-history__dot"></span>
          <b>{{ v.version }}</b>
          <span class="version-history__state" :data-current="v.state === '当前'">{{ v.state }}</span>
          <span class="version-history__date">{{ v.date }}</span>
          <el-icon class="version-history__arrow" :class="{ open: openVersion === v.version }"><ArrowDown /></el-icon>
        </button>
        <div v-if="openVersion === v.version" class="version-history__detail">
          <p class="version-history__note">{{ v.note }} <small>· {{ v.author }}</small></p>
          <div v-if="v.diffs?.length" class="version-diff">
            <p>版本差异</p>
            <div v-for="d in v.diffs" :key="d.label" class="version-diff__row">
              <span class="version-diff__label">{{ d.label }}</span>
              <span class="version-diff__from">{{ d.from }}</span>
              <span class="version-diff__arrow">→</span>
              <span class="version-diff__to">{{ d.to }}</span>
            </div>
          </div>
          <p v-else class="version-history__nodiff">该版本为基线版本，无差异记录</p>
        </div>
      </li>
    </ul>
  </section>
</template>
