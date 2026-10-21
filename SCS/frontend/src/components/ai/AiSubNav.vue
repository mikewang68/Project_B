<script setup lang="ts">
import { Camera, Cpu } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'

const route = useRoute()

defineProps<{
  activeModelName?: string | undefined
  pendingReviewCount?: number | undefined
}>()
</script>

<template>
  <nav class="ai-sub-nav dashboard-card" aria-label="AI识别子模块导航">
    <div class="ai-sub-nav__tabs">
      <RouterLink
        to="/ai"
        class="ai-sub-nav__tab"
        :class="{ 'is-active': route.path === '/ai' }"
      >
        <el-icon><Camera /></el-icon>
        <span>AI违规事件复核</span>
        <span v-if="pendingReviewCount" class="tab-badge">{{ pendingReviewCount }} 待办</span>
      </RouterLink>

      <RouterLink
        to="/ai/models"
        class="ai-sub-nav__tab"
        :class="{ 'is-active': route.path.startsWith('/ai/models') }"
      >
        <el-icon><Cpu /></el-icon>
        <span>AI模型管理与在线识别</span>
        <span v-if="activeModelName" class="tab-pill">当前: {{ activeModelName }}</span>
      </RouterLink>
    </div>

    <div class="ai-sub-nav__status">
      <span class="live-pulse"><i></i><b>AI视觉推理引擎在线</b></span>
    </div>
  </nav>
</template>

<style scoped>
.ai-sub-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  margin-bottom: 14px;
  background: #ffffff;
  border-radius: 12px;
  box-shadow: var(--shadow-card, 0 2px 8px rgba(0,0,0,0.04));
}
.ai-sub-nav__tabs {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ai-sub-nav__tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary, #606266);
  text-decoration: none;
  background: #f4f6f8;
  transition: all 0.2s ease;
}
.ai-sub-nav__tab:hover {
  color: var(--color-primary, #409eff);
  background: #ecf5ff;
}
.ai-sub-nav__tab.is-active {
  color: #ffffff;
  background: var(--color-primary, #409eff);
  box-shadow: 0 2px 6px rgba(64,158,255,0.3);
}
.tab-badge {
  padding: 1px 6px;
  border-radius: 10px;
  font-size: 11px;
  background: #f56c6c;
  color: #ffffff;
}
.ai-sub-nav__tab.is-active .tab-badge {
  background: #ffffff;
  color: #f56c6c;
}
.tab-pill {
  padding: 1px 7px;
  border-radius: 6px;
  font-size: 11px;
  background: #e1f3d8;
  color: #67c23a;
  font-weight: 500;
}
.ai-sub-nav__tab.is-active .tab-pill {
  background: rgba(255,255,255,0.25);
  color: #ffffff;
}
.ai-sub-nav__status {
  display: flex;
  align-items: center;
}
.live-pulse {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #67c23a;
  font-weight: 600;
}
.live-pulse i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #67c23a;
  box-shadow: 0 0 0 3px rgba(103,194,58,0.2);
  animation: pulse 1.8s infinite;
}
@keyframes pulse {
  0% { box-shadow: 0 0 0 1px rgba(103,194,58,0.4); }
  70% { box-shadow: 0 0 0 6px rgba(103,194,58,0); }
  100% { box-shadow: 0 0 0 1px rgba(103,194,58,0); }
}
</style>
