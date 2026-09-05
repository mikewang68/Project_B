<script setup lang="ts">
import { markRaw } from 'vue'
import {
  Aim,
  Bell,
  Camera,
  Cellphone,
  DataAnalysis,
  Location,
  Monitor,
  Operation,
  Setting,
  User,
} from '@element-plus/icons-vue'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const navigation = [
  {
    label: '',
    items: [{ path: '/overview', label: '安全态势', icon: markRaw(Monitor) }],
  },
  {
    label: '作业安全',
    items: [
      { path: '/people', label: '人员定位', icon: markRaw(User) },
      { path: '/fences', label: '电子围栏', icon: markRaw(Location) },
      { path: '/devices', label: '设备防碰撞', icon: markRaw(Aim) },
      { path: '/ai', label: 'AI违规识别', icon: markRaw(Camera) },
    ],
  },
  {
    label: '事件管理',
    items: [
      { path: '/alarms', label: '告警中心', icon: markRaw(Bell) },
      { path: '/analytics', label: '统计分析', icon: markRaw(DataAnalysis) },
    ],
  },
  {
    label: '系统管理',
    items: [
      { path: '/rules', label: '规则配置', icon: markRaw(Setting) },
      { path: '/operations', label: '运维监控', icon: markRaw(Operation) },
    ],
  },
]

const displayEnds = [
  { href: '/safety-screen', label: '安全大屏（全屏）', icon: markRaw(Monitor) },
  { href: '/mobile/home', label: '移动端告警处置', icon: markRaw(Cellphone) },
]
</script>

<template>
  <div v-if="open" class="sidebar-mask" @click="emit('close')"></div>
  <aside class="app-sidebar" :class="{ 'is-open': open }">
    <div class="brand-block">
      <div class="brand-symbol" aria-hidden="true"><span>B</span></div>
      <div class="brand-copy">
        <strong>B项目安全卡控</strong>
        <small>INDUSTRIAL SAFETY</small>
      </div>
    </div>

    <div class="site-selector">
      <span class="site-selector__icon"><el-icon><Location /></el-icon></span>
      <div><small>当前场区</small><b>B区装卸作业场</b></div>
      <i aria-hidden="true">⌄</i>
    </div>

    <nav class="sidebar-nav" aria-label="主导航">
      <section v-for="group in navigation" :key="group.label || 'primary'" class="nav-group">
        <p v-if="group.label">{{ group.label }}</p>
        <RouterLink
          v-for="item in group.items"
          :key="item.path"
          :to="item.path"
          class="nav-link"
          @click="emit('close')"
        >
          <span class="nav-link__icon"><el-icon><component :is="item.icon" /></el-icon></span>
          <span>{{ item.label }}</span>
          <i class="nav-link__active-dot" aria-hidden="true"></i>
        </RouterLink>
      </section>
    </nav>

    <div class="sidebar-display">
      <p>展示端入口（新窗口打开）</p>
      <a
        v-for="item in displayEnds"
        :key="item.href"
        :href="item.href"
        target="_blank"
        rel="noopener"
        class="nav-link nav-link--external"
      >
        <span class="nav-link__icon"><el-icon><component :is="item.icon" /></el-icon></span>
        <span>{{ item.label }}</span>
      </a>
    </div>

    <div class="sidebar-footer">
      <span class="edge-status-icon"><i></i></span>
      <div><b>边缘服务运行中</b><small>EDGE-2.6.1 · 状态正常</small></div>
    </div>
  </aside>
</template>
