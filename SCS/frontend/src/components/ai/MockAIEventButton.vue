<script setup lang="ts">
import { ArrowDown, Cpu, MagicStick, VideoCamera, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElIcon } from 'element-plus'
import type { EdgeScenario } from '@/types/ai'

withDefaults(
  defineProps<{
    edgeOnline?: boolean
    streamRunning?: boolean
    loading?: boolean
  }>(),
  {
    edgeOnline: true,
    streamRunning: false,
    loading: false,
  },
)

const emit = defineEmits<{
  simulate: []
  'low-confidence': []
  'camera-fault': []
  'trigger-edge': [scenario: EdgeScenario]
  'toggle-stream': [action: 'start' | 'stop']
}>()

function handleEdgeCommand(command: string | number | object) {
  emit('trigger-edge', String(command) as EdgeScenario)
}
</script>

<template>
  <div class="ai-control-toolbar">
    <!-- 边缘视觉推理控制组 -->
    <div class="ai-control-toolbar__group">
      <!-- 边缘引擎健康状态指示 -->
      <div class="ai-edge-status-pill" :class="{ 'is-online': edgeOnline }">
        <span class="ai-edge-status-pill__dot"></span>
        <span class="ai-edge-status-pill__label">{{ edgeOnline ? '边缘推理引擎就绪' : '边缘服务离线' }}</span>
      </div>

      <!-- 触发边缘 AI 推理下拉按钮 -->
      <ElDropdown trigger="click" @command="handleEdgeCommand">
        <button type="button" class="ai-toolbar-btn primary" :disabled="loading">
          <ElIcon><Cpu /></ElIcon>
          <span>{{ loading ? '推理计算中…' : '边缘视觉推理' }}</span>
          <ElIcon class="el-icon--right"><ArrowDown /></ElIcon>
        </button>
        <template #dropdown>
          <ElDropdownMenu class="ai-edge-dropdown-menu">
            <ElDropdownItem command="no_helmet">
              <div class="ai-edge-menu-row">
                <span class="ai-edge-menu-row__title">👷 未佩戴安全帽检测</span>
                <span class="ai-edge-menu-row__desc">YOLOv8 PPE 视觉推理 · 定位作业人员与未戴帽头部目标</span>
              </div>
            </ElDropdownItem>
            <ElDropdownItem command="no_vest">
              <div class="ai-edge-menu-row">
                <span class="ai-edge-menu-row__title">🦺 未穿反光衣检测</span>
                <span class="ai-edge-menu-row__desc">YOLOv8 PPE 视觉推理 · 识别高危作业反光防护缺失违规</span>
              </div>
            </ElDropdownItem>
            <ElDropdownItem command="danger_zone" divided>
              <div class="ai-edge-menu-row">
                <span class="ai-edge-menu-row__title">⛔ 危险区域越界入侵</span>
                <span class="ai-edge-menu-row__desc">Shapely 多边形电子围栏 · 实时判定人车越界相交</span>
              </div>
            </ElDropdownItem>
          </ElDropdownMenu>
        </template>
      </ElDropdown>

      <!-- 视频巡检连续推流切换 -->
      <button
        type="button"
        class="ai-toolbar-btn stream-btn"
        :class="{ 'is-active': streamRunning }"
        @click="emit('toggle-stream', streamRunning ? 'stop' : 'start')"
      >
        <ElIcon v-if="streamRunning"><VideoPause /></ElIcon>
        <ElIcon v-else><VideoPlay /></ElIcon>
        <span>{{ streamRunning ? '停止连续巡检' : '开启视频巡检推流' }}</span>
        <span v-if="streamRunning" class="ai-stream-pulse"></span>
      </button>
    </div>

    <!-- 模拟场景控制组（保留完整兼容与边界测试） -->
    <div class="ai-control-toolbar__group ai-control-toolbar__sim">
      <button type="button" class="ai-toolbar-btn ghost" @click="emit('simulate')">
        <ElIcon><MagicStick /></ElIcon>
        <span>模拟标准事件</span>
      </button>
      <button type="button" class="ai-toolbar-btn ghost" @click="emit('low-confidence')">
        <ElIcon><View /></ElIcon>
        <span>模拟低置信度</span>
      </button>
      <button type="button" class="ai-toolbar-btn ghost" @click="emit('camera-fault')">
        <ElIcon><VideoCamera /></ElIcon>
        <span>模拟摄像头异常</span>
      </button>
    </div>
  </div>
</template>
