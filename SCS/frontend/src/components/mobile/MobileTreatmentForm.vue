<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import MobileEvidenceUpload from './MobileEvidenceUpload.vue'
import { MOBILE_MEASURES, type IncidentPhoto } from '@/types/incident'
import { useIncidentStore } from '@/stores/incident'

const props = defineProps<{ incidentId: string }>()
const emit = defineEmits<{ submitted: [] }>()
const store = useIncidentStore()

const measures = reactive<string[]>([])
const siteNote = ref('')
const riskCleared = ref(true)
const note = ref('')
const photos = ref<IncidentPhoto[]>([])
const submitting = ref(false)

function toggle(m: string): void {
  const idx = measures.indexOf(m)
  if (idx >= 0) measures.splice(idx, 1)
  else measures.push(m)
}
function addPhoto(p: IncidentPhoto): void { photos.value.push(p) }
function removePhoto(id: string): void { photos.value = photos.value.filter((p) => p.id !== id) }

async function submit(): Promise<void> {
  if (measures.length === 0) {
    ElMessage.warning('请至少选择一项处置措施')
    return
  }
  submitting.value = true
  try {
    await store.submitTreatment(props.incidentId, {
      measures: [...measures],
      siteNote: siteNote.value || '现场已按规程处置',
      riskCleared: riskCleared.value,
      note: note.value,
      photos: photos.value,
    })
    ElMessage.success('处置结果已提交，等待复核')
    emit('submitted')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '提交失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="m-treatment">
    <h4>现场处置</h4>

    <div class="m-field">
      <label>处置措施（多选）</label>
      <div class="m-chips">
        <button
          v-for="m in MOBILE_MEASURES"
          :key="m"
          type="button"
          class="m-chip"
          :data-on="measures.includes(m)"
          @click="toggle(m)"
        >
          <el-icon v-if="measures.includes(m)"><CircleCheckFilled /></el-icon>{{ m }}
        </button>
      </div>
    </div>

    <div class="m-field">
      <label>现场情况</label>
      <textarea v-model="siteNote" rows="3" placeholder="描述现场实际情况，如：人员已撤离、设备状态等"></textarea>
    </div>

    <div class="m-field">
      <label>风险是否解除</label>
      <div class="m-segmented m-segmented--mini">
        <button type="button" :data-active="riskCleared" @click="riskCleared = true">风险已解除</button>
        <button type="button" :data-active="!riskCleared" @click="riskCleared = false">仍需观察</button>
      </div>
    </div>

    <div class="m-field">
      <label>现场照片 / 证据（Mock，本地留存）</label>
      <MobileEvidenceUpload :photos="photos" @add="addPhoto" @remove="removePhoto" />
    </div>

    <div class="m-field">
      <label>备注</label>
      <textarea v-model="note" rows="2" placeholder="选填"></textarea>
    </div>

    <button type="button" class="m-btn m-btn--primary m-btn--block" :disabled="submitting" @click="submit">提交处理结果</button>
  </section>
</template>
