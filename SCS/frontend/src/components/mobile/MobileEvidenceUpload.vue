<script setup lang="ts">
import { ref } from 'vue'
import { Camera, Delete, Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import type { IncidentPhoto } from '@/types/incident'

const props = defineProps<{ photos: IncidentPhoto[] }>()
const emit = defineEmits<{
  add: [photo: IncidentPhoto]
  remove: [id: string]
}>()

const fileInput = ref<HTMLInputElement>()

function nowTime(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

/** 直接生成一张 Mock 现场照片（SVG 占位图） */
function mockShot(): void {
  const time = nowTime()
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="160">
    <rect width="240" height="160" fill="#dfe7f2"/>
    <rect x="0" y="112" width="240" height="48" fill="#c4d0e2"/>
    <circle cx="60" cy="70" r="26" fill="#aebfd6"/>
    <rect x="110" y="46" width="96" height="64" rx="6" fill="#b7c7dd"/>
    <text x="12" y="26" font-size="13" fill="#315fa8" font-family="sans-serif">现场证据（Mock）</text>
    <text x="12" y="146" font-size="12" fill="#4a5a72" font-family="sans-serif">${time}</text>
  </svg>`
  const thumb = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
  emit('add', { id: `ph-${Date.now()}`, name: `现场照片-${props.photos.length + 1}.jpg`, thumb, time })
  ElMessage.success('现场照片 1 · 上传成功（Mock）')
}

function pickLocal(): void {
  fileInput.value?.click()
}

function onFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    emit('add', { id: `ph-${Date.now()}`, name: file.name, thumb: String(reader.result), time: nowTime() })
    ElMessage.success('本地图片已加入现场证据')
  }
  reader.readAsDataURL(file)
  input.value = ''
}
</script>

<template>
  <div class="m-upload">
    <div class="m-upload__actions">
      <button type="button" class="m-btn m-btn--ghost" @click="mockShot">
        <el-icon><Camera /></el-icon>拍照（Mock）
      </button>
      <button type="button" class="m-btn m-btn--ghost" @click="pickLocal">
        <el-icon><Picture /></el-icon>选择本地图片
      </button>
      <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />
    </div>
    <div v-if="props.photos.length" class="m-upload__list">
      <div v-for="p in props.photos" :key="p.id" class="m-upload__item">
        <img :src="p.thumb === 'mock' ? undefined : p.thumb" alt="现场证据" />
        <span v-if="p.thumb === 'mock'" class="m-upload__placeholder">已上传</span>
        <div>
          <b>{{ p.name }}</b>
          <small>{{ p.time }} · 上传成功</small>
        </div>
        <button type="button" class="m-upload__del" @click="emit('remove', p.id)">
          <el-icon><Delete /></el-icon>
        </button>
      </div>
    </div>
  </div>
</template>
