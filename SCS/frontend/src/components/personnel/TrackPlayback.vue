<script setup lang="ts">
import { Close, VideoPause, VideoPlay } from '@element-plus/icons-vue'

defineProps<{ playing: boolean; index: number; total: number; speed: number; time: string }>()
defineEmits<{ play: []; pause: []; seek: [index: number]; speed: [speed: number]; exit: [] }>()
</script>

<template>
  <div class="track-playback">
    <button type="button" class="track-playback__main" @click="playing ? $emit('pause') : $emit('play')"><el-icon><VideoPause v-if="playing" /><VideoPlay v-else /></el-icon></button>
    <div class="track-playback__timeline"><span>{{ time }}</span><input type="range" min="0" :max="Math.max(total - 1, 0)" :value="index" @input="$emit('seek', Number(($event.target as HTMLInputElement).value))" /></div>
    <button type="button" :class="{ active: speed === 1 }" @click="$emit('speed', 1)">1x</button>
    <button type="button" :class="{ active: speed === 2 }" @click="$emit('speed', 2)">2x</button>
    <button type="button" class="track-playback__exit" @click="$emit('exit')"><el-icon><Close /></el-icon>退出回放</button>
  </div>
</template>
