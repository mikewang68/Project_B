<script setup lang="ts">
import { ref } from 'vue'
import { ElDrawer } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import type { RepeatPerson } from '@/types/analytics'

defineProps<{ items: RepeatPerson[] }>()
const emit = defineEmits<{ drill: [name: string] }>()
const current = ref<RepeatPerson>()
const drawerOpen = ref(false)

function open(p: RepeatPerson): void {
  current.value = p
  drawerOpen.value = true
  emit('drill', p.name)
}
</script>

<template>
  <div class="chart-card repeat-person">
    <header>
      <div><span>REPEATED ATTENTION</span><h3>重复风险人员</h3></div>
      <small>用于安全辅导与排班关注</small>
    </header>
    <ul class="repeat-person__list">
      <li v-for="p in items" :key="p.personId" @click="open(p)">
        <span class="repeat-person__avatar">{{ p.name.slice(0, 1) }}</span>
        <div class="repeat-person__main"><b>{{ p.name }}</b><small>{{ p.team }} · {{ p.mainType }}</small></div>
        <span class="repeat-person__count">{{ p.count }} 次</span>
      </li>
    </ul>

    <ElDrawer :model-value="drawerOpen" size="420px" :with-header="false" class="person-mini-drawer"
      @update:model-value="drawerOpen = $event">
      <template v-if="current">
        <header class="person-mini-header">
          <div><span>PERSONNEL PROFILE</span>
            <h2><i>{{ current.name.slice(0, 1) }}</i>{{ current.name }}<small>{{ current.personId }}</small></h2>
            <p>安全辅导记录（Mock，非违规黑名单）</p>
          </div>
          <button type="button" aria-label="关闭" @click="drawerOpen = false"><el-icon><Close /></el-icon></button>
        </header>
        <div class="person-mini-scroll">
          <div class="person-mini-grid">
            <div><small>所在班组</small><b>{{ current.team }}</b></div>
            <div><small>累计提醒</small><b>{{ current.count }} 次</b></div>
            <div class="wide"><small>主要风险类型</small><b>{{ current.mainType }}</b></div>
          </div>
          <section>
            <h4>最近事件</h4>
            <ul class="person-mini-events">
              <li v-for="(r, i) in current.recent" :key="i"><span></span>{{ r }}</li>
            </ul>
          </section>
          <p class="person-mini-note">建议：班前安全提醒、作业路线复核与同伴互保，数据仅用于安全管理改进。</p>
        </div>
      </template>
    </ElDrawer>
  </div>
</template>
