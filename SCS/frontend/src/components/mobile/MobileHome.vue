<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Avatar, CircleCheck, Clock, WarningFilled } from '@element-plus/icons-vue'
import { useIncidentStore } from '@/stores/incident'
import { useLiveStore } from '@/stores/live'

const store = useIncidentStore()
const liveStore = useLiveStore()
const router = useRouter()

// 仅统计已派发现场之后的事件（待确认/待派单属于管理端流程，不进入移动端首页）
const DISPATCHED = ['待处理', '处理中', '待复核', '已关闭', '已升级']
const pending = computed(() =>
  store.incidents.filter((i) => i.status === '待接单' && DISPATCHED.includes(i.alertStatus)))
const handling = computed(() =>
  store.incidents.filter((i) => ['已接单', '已到场', '处理中'].includes(i.status) && DISPATCHED.includes(i.alertStatus)))
const critical = computed(() =>
  store.incidents.filter((i) => i.risk === '紧急' && i.status !== '已关闭' && DISPATCHED.includes(i.alertStatus)))

const cards = computed(() => [
  { label: '待处理', value: store.home?.pending ?? store.pendingCount, tone: 'red' },
  { label: '处理中', value: store.home?.handling ?? handling.value.length, tone: 'amber' },
  { label: '紧急', value: store.home?.urgent ?? critical.value.length, tone: 'danger' },
  { label: '今日完成', value: store.closedToday, tone: 'green' },
])

const userName = computed(() => store.home?.userName ?? '王建国')
const userMeta = computed(() => {
  const h = store.home
  return h ? `${h.role} · ${h.team} · ${h.shift}班 22:00-06:00` : '安全员 · 装卸一班 · 夜班 22:00-06:00'
})
</script>

<template>
  <div class="m-home">
    <header class="m-hero">
      <div class="m-hero__user">
        <span class="m-hero__avatar"><el-icon :size="22"><Avatar /></el-icon></span>
        <div>
          <b>{{ userName }}</b>
          <small>{{ userMeta }}</small>
        </div>
      </div>
      <span class="m-hero__online" :data-online="liveStore.online">
        <i></i>{{ liveStore.online ? '在线' : '重连中' }}
      </span>
    </header>

    <section class="m-stat-grid">
      <div v-for="c in cards" :key="c.label" class="m-stat" :data-tone="c.tone">
        <b>{{ c.value }}</b><span>{{ c.label }}</span>
      </div>
    </section>

    <section class="m-block">
      <div class="m-block__head">
        <h3><el-icon><WarningFilled /></el-icon>待接单告警</h3>
        <RouterLink to="/mobile/alerts" class="m-block__more">全部<el-icon><ArrowRight /></el-icon></RouterLink>
      </div>
      <div v-if="pending.length === 0" class="m-empty">
        <el-icon :size="22"><CircleCheck /></el-icon>
        <p>暂无待接单告警，现场风险平稳</p>
      </div>
      <RouterLink
        v-for="i in pending"
        :key="i.id"
        :to="`/mobile/alert/${i.id}`"
        class="m-quick-row"
        :data-risk="i.risk"
      >
        <span class="m-quick-row__risk">{{ i.risk }}</span>
        <div>
          <b>{{ i.title }}</b>
          <small>{{ i.area }} · {{ i.time }} · 距你 {{ i.distanceM }}m</small>
        </div>
        <el-icon class="m-quick-row__go"><ArrowRight /></el-icon>
      </RouterLink>
    </section>

    <section class="m-block">
      <div class="m-block__head"><h3><el-icon><Clock /></el-icon>处置中</h3></div>
      <div v-for="i in handling" :key="i.id" class="m-quick-row" :data-risk="i.risk" @click="router.push(`/mobile/alert/${i.id}`)">
        <span class="m-quick-row__risk">{{ i.risk }}</span>
        <div>
          <b>{{ i.title }}</b>
          <small>{{ i.area }} · {{ i.status }}</small>
        </div>
        <el-icon class="m-quick-row__go"><ArrowRight /></el-icon>
      </div>
    </section>
  </div>
</template>
