<script setup lang="ts">
import { ref } from 'vue'
import { Delete, Picture } from '@element-plus/icons-vue'
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
