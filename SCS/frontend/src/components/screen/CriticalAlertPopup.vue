<script setup lang="ts">
import { ref, watch } from 'vue'
import { Aim, CircleCheck, WarningFilled } from '@element-plus/icons-vue'
import type { MobileIncident } from '@/types/incident'

const props = defineProps<{ incident: MobileIncident | null }>()
const emit = defineEmits<{ acknowledge: []; locate: [incident: MobileIncident] }>()

const duration = ref(0)
let timer: number | undefined

watch(() => props.incident?.id, () => {
  duration.value = props.incident ? 8 : 0
  window.clearInterval(timer)
  if (props.incident) {
    timer = window.setInterval(() => { duration.value += 1 }, 1000)
  }
}, { immediate: true })
</script>

<template>
  <Transition name="bs-critical">
    <div v-if="props.incident" class="bs-critical">
      <div class="bs-critical__card">
        <header>
          <span class="bs-critical__tag"><el-icon :size="15"><WarningFilled /></el-icon>紧急安全事件</span>
          <span class="bs-critical__pulse"></span>
        </header>
        <h3>{{ props.incident.title }}</h3>
        <dl>
          <div><dt>人员 / 对象</dt><dd>{{ props.incident.target }}</dd></div>
          <div><dt>区域</dt><dd>{{ props.incident.area }}</dd></div>
          <div><dt>触发时间</dt><dd>{{ props.incident.time }}</dd></div>
          <div><dt>持续时间</dt><dd>{{ duration }} 秒</dd></div>
          <div><dt>当前状态</dt><dd :data-state="props.incident.status">{{ props.incident.status }}</dd></div>
          <div><dt>联动状态</dt><dd>现场声光已触发 · 等待现场处置</dd></div>
        </dl>
        <footer>
          <button type="button" class="bs-critical__locate" @click="emit('locate', props.incident!)">
            <el-icon><Aim /></el-icon>查看位置
          </button>
          <button type="button" class="bs-critical__ack" @click="emit('acknowledge')">
            <el-icon><CircleCheck /></el-icon>确认已读
          </button>
        </footer>
        <p class="bs-critical__note">大屏端为只读展示，设备停机与规则变更请在管理端执行</p>
      </div>
    </div>
  </Transition>
</template>
