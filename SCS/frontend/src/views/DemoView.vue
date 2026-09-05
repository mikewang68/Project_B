<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import LevelTag from '@/components/LevelTag.vue'
import { useSafetyStore } from '@/stores/safety'

const store = useSafetyStore()
const running = ref<string>()

async function run(id: string): Promise<void> {
  if (store.usingFallback) {
    ElMessage.info('当前为前端演示快照；启动后端后可触发完整模拟。')
    return
  }
  running.value = id
  try {
    await store.triggerScenario(id)
    ElMessage.success('场景已触发，告警与审计已更新')
  } finally {
    running.value = undefined
  }
}
</script>

<template>
  <section class="page-content">
    <div class="demo-hero">
      <div><span>SCENARIO DEMONSTRATION</span><h2>从风险感知到闭环复盘，一键演示</h2><p>场景仅生成模拟数据，不连接 PLC、短信平台或现场设备。紧急规则到接口目标 ≤ 2 秒，现场硬联锁仍归边缘与 PLC。</p></div>
      <el-button type="primary" size="large" @click="run('person_intrusion')">开始推荐演示</el-button>
    </div>
    <div class="demo-steps"><span class="active"><i>1</i>选择场景</span><b></b><span><i>2</i>触发事件</span><b></b><span><i>3</i>查看联动</span><b></b><span><i>4</i>完成处置</span><b></b><span><i>5</i>复盘审计</span></div>
    <div class="scenario-grid">
      <article v-for="scenario in store.snapshot.scenarios" :key="scenario.id" class="scenario-card" :data-level="scenario.level">
        <div><LevelTag :level="scenario.level" /><span>{{ scenario.domain }}</span></div>
        <h3>{{ scenario.name }}</h3><p>{{ scenario.description }}</p>
        <footer><small>{{ scenario.duration }}</small><el-button :loading="running === scenario.id" @click="run(scenario.id)">触发场景</el-button></footer>
      </article>
    </div>
    <div class="demo-bottom">
      <article class="panel"><header><div><span>LINKAGE RECEIPTS</span><h2>多通道联动状态</h2></div></header><div class="receipt-grid"><span><i></i>大屏弹窗<b>已送达</b></span><span><i></i>声光告警<b>已执行</b></span><span><i></i>站内消息<b>已确认</b></span><span><i></i>控制接口<b>等待回执</b></span></div></article>
      <article class="panel"><header><div><span>AUDIT TRAIL</span><h2>最近执行审计</h2></div></header><div v-for="log in store.snapshot.auditLogs.slice(0, 4)" :key="log.id" class="audit-row"><span>{{ new Date(log.time).toLocaleTimeString('zh-CN', { hour12: false }) }}</span><b>{{ log.action }}</b><em>{{ log.result }}</em></div></article>
    </div>
  </section>
</template>

