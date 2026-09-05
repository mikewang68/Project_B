<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useSafetyStore } from '@/stores/safety'
import { useLiveStore } from '@/stores/live'

const route = useRoute()
const store = useSafetyStore()
const liveStore = useLiveStore()
const sidebarOpen = ref(false)

const pageTitle = computed(() => String(route.meta.title ?? '安全态势'))
const pageSubtitle = computed(() => String(route.meta.subtitle ?? '装卸作业安全运营与风险协同'))

onMounted(() => {
  // 全局只维护一条 /ws/live（管理端 / 大屏 / 移动端共享）
  liveStore.start()
  void store.initialize()
})

onBeforeUnmount(() => {
  store.dispose()
})
</script>

<template>
  <RouterView v-if="route.meta.standalone" />
  <div v-else class="app-shell">
    <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />
    <div class="app-main">
      <AppHeader
        :title="pageTitle"
        :subtitle="pageSubtitle"
        :shift="store.snapshot.workPlan.shift"
        :connection="store.connection"
        :using-fallback="store.usingFallback"
        @menu="sidebarOpen = true"
      />
      <div v-if="store.error" class="global-error" role="alert">{{ store.error }}</div>
      <RouterView />
    </div>
  </div>
</template>
