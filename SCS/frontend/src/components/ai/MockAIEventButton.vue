<script setup lang="ts">
import { MagicStick, View, VideoCamera } from '@element-plus/icons-vue'

defineEmits<{ simulate: []; 'low-confidence': []; 'camera-fault': [] }>()
</script>

<template>
  <div class="ai-mock-actions">
    <button type="button" class="primary" @click="$emit('simulate')"><el-icon><MagicStick /></el-icon>模拟 AI 事件</button>
    <button type="button" @click="$emit('low-confidence')"><el-icon><View /></el-icon>模拟低置信度</button>
    <button type="button" @click="$emit('camera-fault')"><el-icon><VideoCamera /></el-icon>模拟摄像头异常</button>
  </div>
</template>
