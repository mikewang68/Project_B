<script setup lang="ts">
import type { CloudLink, EdgeNode } from '@/types/operations'
import type { DeviceCategory } from '@/types/operations'
import { onlineCount } from '@/adapters/operations'

const props = defineProps<{
  link: CloudLink
  nodes: EdgeNode[]
  categories: DeviceCategory[]
}>()

const platformState = () => {
  if (props.link === 'online') return 'normal'
  if (props.link === 'link-error') return 'degraded'
  if (props.link === 'recovering') return 'degraded'
  return 'fault'
}
const platformText = () => {
  if (props.link === 'online') return '运行正常'
  if (props.link === 'link-error') return '链路异常'
  if (props.link === 'recovering') return '恢复中'
  return '连接中断'
}
const edgeStateOf = (n: EdgeNode): 'normal' | 'degraded' | 'fault' | 'offline' => {
  if (!n.online) return 'offline'
  if (props.link === 'disconnected' || props.link === 'link-error') return 'degraded'
  if (props.link === 'recovering') return 'degraded'
  return 'normal'
}
const edgeTextOf = (n: EdgeNode): string => {
  const s = edgeStateOf(n)
  if (s === 'degraded') return props.link === 'recovering' ? '同步中' : '本地自治'
  if (s === 'offline') return '离线'
  return '在线'
}
const linkText = () => {
  if (props.link === 'online') return '链路正常'
  if (props.link === 'link-error') return '链路抖动'
  if (props.link === 'recovering') return '链路恢复中'
  return '链路中断'
}
</script>

<template>
  <div class="dashboard-card ops-topology">
    <div class="ops-card-head">
      <div><span>CLOUD — EDGE — DEVICE</span><h3>云边端运行拓扑</h3></div>
      <span class="topo-link" :data-state="platformState()"><i></i>{{ linkText() }}</span>
    </div>

    <div class="topo-flow">
      <!-- 第一层：中心平台 -->
      <div class="topo-layer">
        <div class="topo-platform" :data-state="platformState()">
          <div class="topo-platform__main">
            <b>中心平台</b>
            <small>{{ platformText() }}</small>
          </div>
          <div class="topo-platform__services">
            <span>事件中心</span>
            <span>规则中心</span>
            <span>数据服务</span>
          </div>
        </div>
      </div>

      <div class="topo-bus" :data-state="platformState()">
        <span class="topo-bus__line"></span>
        <em>{{ linkText() }}</em>
        <span class="topo-bus__line"></span>
      </div>

      <!-- 第二层：边缘节点 -->
      <div class="topo-layer topo-layer--edges">
        <div v-for="n in nodes" :key="n.id" class="topo-edge" :data-state="edgeStateOf(n)">
          <b>{{ n.id }}</b>
          <small>{{ edgeTextOf(n) }}</small>
          <i class="topo-edge__rule">{{ n.ruleVersion }}</i>
        </div>
      </div>

      <div class="topo-bus topo-bus--thin" data-state="normal">
        <span class="topo-bus__line"></span>
        <em>本地总线 · 断网期间保持运行</em>
        <span class="topo-bus__line"></span>
      </div>

      <!-- 第三层：设备 -->
      <div class="topo-layer topo-layer--devices">
        <div v-for="c in categories" :key="c.kind" class="topo-device">
          <b>{{ c.kind }}</b>
          <small>{{ onlineCount(c.devices) }} / {{ c.total }} 在线</small>
        </div>
      </div>
    </div>
  </div>
</template>
