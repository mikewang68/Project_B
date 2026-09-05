<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import type { RuleConflict } from '@/types/rule'

const props = defineProps<{ modelValue: boolean; conflicts: RuleConflict[] }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  view: [conflict: RuleConflict]
  ignore: []
}>()

const confirmStage = ref(false)
watch(() => props.modelValue, () => { confirmStage.value = false })
function ignoreAndClose(): void {
  emit('ignore')
  confirmStage.value = false
  emit('update:modelValue', false)
}
</script>

<template>
  <ElDialog :model-value="modelValue" width="560px" class="rule-conflict-dialog" :show-close="false"
    @update:model-value="emit('update:modelValue', $event)">
    <template #header>
      <div class="rule-dialog-heading"><span>CONFLICT CHECK</span>
        <h2>规则冲突检查</h2><p>同区域、同设备类型的规则阈值一致性校验（Mock）</p></div>
    </template>

    <template v-if="conflicts.length">
      <div v-for="c in conflicts" :key="c.ruleA + c.ruleB" class="conflict-card">
        <div class="conflict-card__title">
          <el-icon><WarningFilled /></el-icon>
          <span>发现 {{ conflicts.length }} 条规则冲突 · {{ c.area }} · {{ c.deviceKind }}</span>
        </div>
        <div class="conflict-compare">
          <div class="conflict-side">
            <b>{{ c.ruleA }}</b>
            <small>{{ c.paramLabel }}</small>
            <strong>{{ c.valueA }}</strong>
          </div>
          <span class="conflict-vs">VS</span>
          <div class="conflict-side">
            <b>{{ c.ruleB }}</b>
            <small>{{ c.paramLabel }}</small>
            <strong>{{ c.valueB }}</strong>
          </div>
        </div>
        <p class="conflict-desc">两条规则在「{{ c.area }}」对{{ c.deviceKind }}的{{ c.paramLabel }}阈值设置不一致，可能导致同一场景判定结果不同。</p>

        <div v-if="c.highRisk && confirmStage" class="conflict-confirm">
          <el-icon><WarningFilled /></el-icon>
          <div>
            <b>该冲突涉及严重级安全规则</b>
            <p>忽略后可能导致现场判定不一致，建议先返回修改。仍要忽略并保存吗？</p>
          </div>
        </div>
      </div>
    </template>
    <div v-else class="conflict-ok">
      <b>未发现规则冲突</b>
      <p>同区域同设备类型的阈值保持一致，规则可以正常保存 / 发布。</p>
    </div>

    <template #footer>
      <template v-if="conflicts.length">
        <button type="button" class="rule-btn ghost" @click="emit('update:modelValue', false)">返回修改</button>
        <button v-if="!confirmStage" type="button" class="rule-btn" @click="conflicts[0] && emit('view', conflicts[0])">查看冲突</button>
        <button v-if="!confirmStage" type="button" class="rule-btn danger" @click="confirmStage = true">忽略并保存</button>
        <template v-else>
          <button type="button" class="rule-btn danger" @click="confirmStage = false">再想想</button>
          <button type="button" class="rule-btn danger" @click="ignoreAndClose">仍要忽略</button>
        </template>
      </template>
      <button v-else type="button" class="rule-btn primary" @click="emit('update:modelValue', false)">知道了</button>
    </template>
  </ElDialog>
</template>
