<script setup lang="ts">
import { Warning } from '@element-plus/icons-vue'
import { AUTONOMY_CAPABILITIES } from '@/types/operations'
</script>

<template>
  <div class="offline-banner">
    <div class="offline-banner__main">
      <el-icon class="offline-banner__icon"><Warning /></el-icon>
      <div>
        <b>中心链路中断，边缘节点已进入本地自治模式，关键安全卡控能力继续运行。</b>
        <p>人员定位、电子围栏、设备防碰撞与紧急联动由边缘节点本地执行；事件先缓存，链路恢复后自动补传与对账。</p>
      </div>
    </div>
    <div class="offline-banner__caps">
      <span v-for="c in AUTONOMY_CAPABILITIES" :key="c.name" :data-ok="c.available">
        <i>{{ c.available ? '✓' : '⏸' }}</i>{{ c.name }} · {{ c.note }}
      </span>
    </div>
  </div>
</template>
