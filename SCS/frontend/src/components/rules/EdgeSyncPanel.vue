<script setup lang="ts">
import { Loading, Check, Warning } from '@element-plus/icons-vue'
import type { EdgeNodeState } from '@/types/rule'

defineProps<{ nodes: EdgeNodeState[]; platformVersion: string }>()
</script>

<template>
  <section class="edge-sync">
    <div class="edge-sync__head">
      <h4>边缘同步状态</h4>
      <span class="edge-sync__platform">平台版本 <b>{{ platformVersion }}</b></span>
    </div>
    <ul class="edge-sync__list">
      <li v-for="n in nodes" :key="n.node" :data-state="n.state">
        <span class="edge-sync__node">{{ n.node }}</span>
        <span class="edge-sync__version">{{ n.version }}</span>
        <span class="edge-sync__state">
          <el-icon v-if="n.state === 'synced'"><Check /></el-icon>
          <el-icon v-else-if="n.state === 'syncing'" class="spin"><Loading /></el-icon>
          <el-icon v-else><Warning /></el-icon>
          {{ n.state === 'synced' ? '已同步' : n.state === 'syncing' ? '同步中' : '版本不一致' }}
        </span>
      </li>
    </ul>
  </section>
</template>
