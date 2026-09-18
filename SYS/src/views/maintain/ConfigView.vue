<script setup lang="ts">
import { reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSysStore } from '@/stores/sys'
import { useAuthStore } from '@/stores/auth'
import type { SysConfigItem } from '@/sys/types'

const sys = useSysStore()
const auth = useAuthStore()
const canEdit = computed(() => auth.isSuperAdmin || auth.permCodes.has('sys:config:list:edit'))

// 编辑草稿（统一字符串存储），配置加载后取当前值
const draft = reactive<Record<string, string>>({})

onMounted(async () => {
  try {
    await sys.bootstrap()
    sys.configs.forEach((c) => {
      if (draft[c.key] === undefined) draft[c.key] = c.value
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '系统配置加载失败')
  }
})

const GROUP_ORDER = ['基础设置', '安全策略', '会话设置']
const groups = computed(() =>
  GROUP_ORDER.map((g) => ({ name: g, items: sys.configs.filter((c) => c.group === g) })).filter((g) => g.items.length > 0),
)

function numVal(c: SysConfigItem) {
  return Number(draft[c.key] ?? c.value)
}
function setNum(c: SysConfigItem, v: number | undefined) {
  draft[c.key] = String(v ?? 0)
}
function boolVal(c: SysConfigItem) {
  return (draft[c.key] ?? c.value) === 'true'
}
function setBool(c: SysConfigItem, v: boolean | string | number) {
  draft[c.key] = String(Boolean(v))
}

/** 某组是否有未保存修改 */
function groupDirty(items: SysConfigItem[]) {
  return items.some((c) => draft[c.key] !== c.value)
}

async function saveGroup(groupName: string, items: SysConfigItem[]) {
  const entries = items.map((c) => ({ key: c.key, value: draft[c.key] ?? c.value }))
  try {
    const n = await sys.saveConfigGroup(entries)
    // 保存后以后端返回值为准同步草稿
    sys.configs.forEach((c) => (draft[c.key] = c.value))
    if (n === 0) ElMessage.info('配置没有变化')
    else ElMessage.success(`「${groupName}」已保存，更新 ${n} 项`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}
</script>

<template>
  <div class="config-page">
    <el-alert
      v-if="!canEdit"
      title="当前账号为只读权限，可查看配置但不能修改；如需调整请联系管理员在 IAM 授予编辑权限。"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom:14px"
    />

    <el-card v-for="g in groups" :key="g.name" shadow="never" class="group-card">
      <template #header>
        <div class="group-header">
          <span class="panel-title">{{ g.name }}</span>
          <el-button
            v-if="canEdit"
            type="primary"
            size="small"
            :disabled="!groupDirty(g.items)"
            @click="saveGroup(g.name, g.items)"
          >保存本组</el-button>
        </div>
      </template>

      <el-form label-width="160px" class="config-form">
        <el-form-item v-for="c in g.items" :key="c.key">
          <template #label>
            <span class="cfg-label">{{ c.label }}</span>
          </template>

          <!-- 字符串 -->
          <el-input
            v-if="c.type === 'string'"
            v-model="draft[c.key]"
            :disabled="!canEdit"
            style="max-width:360px"
          />
          <!-- 数值 -->
          <el-input-number
            v-else-if="c.type === 'number'"
            :model-value="numVal(c)"
            @update:model-value="(v: number | undefined) => setNum(c, v)"
            :disabled="!canEdit"
          />
          <!-- 布尔 -->
          <el-switch
            v-else-if="c.type === 'boolean'"
            :model-value="boolVal(c)"
            @update:model-value="(v: boolean | string | number) => setBool(c, v)"
            :disabled="!canEdit"
            active-text="开启"
            inactive-text="关闭"
          />
          <!-- 下拉 -->
          <el-select
            v-else-if="c.type === 'select'"
            v-model="draft[c.key]"
            :disabled="!canEdit"
            style="min-width:240px"
          >
            <el-option v-for="o in c.options || []" :key="o" :label="o" :value="o" />
          </el-select>

          <span v-if="c.unit" class="cfg-unit">{{ c.unit }}</span>
          <div v-if="c.remark" class="cfg-remark">{{ c.remark }}（参数键：{{ c.key }}）</div>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.config-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 900px;
}
.group-card {
  .panel-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--iam-text-strong);
  }
}
.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cfg-label {
  font-size: 13px;
  color: var(--iam-text-base);
}
.cfg-unit {
  margin-left: 10px;
  font-size: 12px;
  color: var(--iam-text-muted);
}
.cfg-remark {
  width: 100%;
  font-size: 11px;
  color: var(--iam-text-muted);
  margin-top: 4px;
  line-height: 1.5;
}
</style>
