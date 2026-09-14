<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { Bell, Back, Connection, House, Loading } from '@element-plus/icons-vue'
import { useIncidentStore } from '@/stores/incident'
import { useLiveStore } from '@/stores/live'

const store = useIncidentStore()
const liveStore = useLiveStore()
const route = useRoute()
const clock = ref('')
let timer: number | undefined

function tick(): void {
  clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
}
onMounted(() => {
  tick()
  timer = window.setInterval(tick, 1000 * 20)
  void store.loadAll()
})
onBeforeUnmount(() => window.clearInterval(timer))

const activeTab = computed(() => route.path.includes('/alerts') || route.path.includes('/alert/') ? 'alerts' : 'home')
const reconnecting = computed(() => liveStore.status === 'reconnecting' || liveStore.status === 'connecting')
</script>

<template>
  <div class="mobile-viewport">
    <div class="mobile-device">
      <div class="mobile-statusbar">
        <span>{{ clock }}</span>
        <span class="mobile-statusbar__right">
          <RouterLink to="/overview" class="mobile-back-console" title="返回管理后台">
            <el-icon :size="13"><Back /></el-icon>后台
          </RouterLink>
          <span class="mobile-net" :data-online="liveStore.online">
            <el-icon><component :is="reconnecting ? Loading : Connection" /></el-icon>
            {{ liveStore.online ? '在线' : reconnecting ? '重连中' : '实时连接断开' }}
          </span>
        </span>
      </div>

      <div v-if="!liveStore.online" class="mobile-offline-tip">
        实时连接中断，正在自动重连；已加载告警可继续查看与处置，恢复后自动同步。
      </div>

      <main class="mobile-screen">
        <RouterView />
      </main>

      <nav class="mobile-tabbar">
        <RouterLink to="/mobile/home" :data-active="activeTab === 'home'">
          <el-icon :size="20"><House /></el-icon><span>首页</span>
        </RouterLink>
        <RouterLink to="/mobile/alerts" :data-active="activeTab === 'alerts'" class="mobile-tabbar__bell">
          <el-icon :size="20"><Bell /></el-icon><span>告警</span>
          <i v-if="store.pendingCount > 0">{{ store.pendingCount }}</i>
        </RouterLink>
      </nav>
    </div>
  </div>
</template>
