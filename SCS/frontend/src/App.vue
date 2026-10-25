<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import { useSafetyStore } from '@/stores/safety'
import { useAuthStore } from '@/stores/auth'
import { sharedLiveSocket } from '@/api/live'
import { useLiveStore } from '@/stores/live'

const auth=useAuthStore()
const route = useRoute()
const store = useSafetyStore()
const liveStore = useLiveStore()
const sidebarOpen = ref(false)
let authorizationTimer:number|undefined

const pageTitle = computed(() => String(route.meta.title ?? '安全态势'))
const pageSubtitle = computed(() => String(route.meta.subtitle ?? '装卸作业安全运营与风险协同'))

onMounted(async () => {
  // 全局只维护一条 /ws/live（管理端 / 大屏 / 移动端共享）
  if(!await auth.ensureLoaded())return
  authorizationTimer=window.setInterval(()=>{void auth.refresh()},15_000)
  liveStore.start()
  void store.initialize()
})

watch(()=>auth.user?.authorizationVersion,(next,previous)=>{
  if(previous&&next&&next!==previous){store.dispose();void store.initialize()}
})
function clearAuthentication(){auth.clear();sharedLiveSocket.stop();store.dispose()}
onMounted(()=>window.addEventListener('scs:unauthenticated',clearAuthentication))
onBeforeUnmount(() => {
  window.removeEventListener('scs:unauthenticated',clearAuthentication)
  if(authorizationTimer!==undefined)window.clearInterval(authorizationTimer)
  store.dispose()
})
</script>

<template>
  <main v-if="!auth.user" role="alert"><h1>请通过统一入口登录</h1><p>{{auth.error}}</p><a href="/sso/start?page=SCS:overview">统一登录</a></main>
  <RouterView v-else-if="route.meta.standalone" />
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
