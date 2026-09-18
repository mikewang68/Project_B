<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useSysStore } from '@/stores/sys'
import { useAuthStore } from '@/stores/auth'
import type { DictType, DictItem, Status } from '@/sys/types'

const sys = useSysStore()
const auth = useAuthStore()
const can = (code: string) => auth.isSuperAdmin || auth.permCodes.has(code)

onMounted(async () => {
  try {
    await sys.bootstrap()
    if (!currentTypeCode.value && sys.dictTypes.length > 0) {
      currentTypeCode.value = sys.dictTypes.slice().sort((a, b) => a.code.localeCompare(b.code))[0].code
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '字典数据加载失败')
  }
})

// ============ 左侧：字典分类 ============
const typeKeyword = ref('')
const filteredTypes = computed(() =>
  sys.dictTypes
    .filter((t) => !typeKeyword.value || t.name.includes(typeKeyword.value) || t.code.includes(typeKeyword.value))
    .slice()
    .sort((a, b) => a.code.localeCompare(b.code)),
)
const currentTypeCode = ref<string>('')
const currentType = computed(() => sys.dictTypes.find((t) => t.code === currentTypeCode.value))
function selectType(code: string) {
  currentTypeCode.value = code
}

const typeDialog = ref(false)
const typeSaving = ref(false)
const typeFormRef = ref<FormInstance>()
const typeEditing = ref<DictType | null>(null)
const typeForm = reactive({ code: '', name: '', status: 'active' as Status, remark: '' })
const typeRules: FormRules = {
  code: [
    { required: true, message: '请输入类型编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_]*$/, message: '编码需为小写字母/数字/下划线，且字母开头', trigger: 'blur' },
    {
      validator: (_r, v: string, cb) => {
        const dup = sys.dictTypes.some((t) => t.code === v && t.id !== typeEditing.value?.id)
        dup ? cb(new Error('该编码已存在')) : cb()
      },
      trigger: 'blur',
    },
  ],
  name: [{ required: true, message: '请输入类型名称', trigger: 'blur' }],
}

function openTypeAdd() {
  typeEditing.value = null
  Object.assign(typeForm, { code: '', name: '', status: 'active', remark: '' })
  typeDialog.value = true
}
function openTypeEdit(row: DictType) {
  typeEditing.value = row
  Object.assign(typeForm, { code: row.code, name: row.name, status: row.status, remark: row.remark || '' })
  typeDialog.value = true
}
async function submitType() {
  if (typeSaving.value) return
  await typeFormRef.value?.validate()
  typeSaving.value = true
  try {
    if (typeEditing.value) {
      await sys.updateType(typeEditing.value.id, { name: typeForm.name, status: typeForm.status, remark: typeForm.remark })
      ElMessage.success('分类已更新')
    } else {
      const created = await sys.addType({ code: typeForm.code, name: typeForm.name, status: typeForm.status, remark: typeForm.remark })
      currentTypeCode.value = created.code
      ElMessage.success('分类已新增')
    }
    typeDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    typeSaving.value = false
  }
}
async function removeType(row: DictType) {
  const n = sys.dictItems.filter((i) => i.typeCode === row.code).length
  try {
    await ElMessageBox.confirm(
      `删除分类「${row.name}」将同时删除其下 ${n} 个字典项，且不可恢复，确认删除？`,
      '删除字典分类',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await sys.removeType(row.id)
    if (currentTypeCode.value === row.code) {
      currentTypeCode.value = sys.dictTypes.slice().sort((a, b) => a.code.localeCompare(b.code))[0]?.code || ''
    }
    ElMessage.success('分类已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ============ 右侧：字典项 ============
const itemKeyword = ref('')
const currentItems = computed(() =>
  sys
    .itemsOf(currentTypeCode.value)
    .filter((i) => !itemKeyword.value || i.label.includes(itemKeyword.value) || i.value.includes(itemKeyword.value)),
)

const itemDialog = ref(false)
const itemSaving = ref(false)
const itemFormRef = ref<FormInstance>()
const itemEditing = ref<DictItem | null>(null)
const itemForm = reactive({ label: '', value: '', sort: 1, status: 'active' as Status, tagType: 'info' as NonNullable<DictItem['tagType']>, remark: '' })
const TAG_OPTIONS = [
  { v: 'primary', l: '主色' },
  { v: 'success', l: '成功/绿' },
  { v: 'warning', l: '警告/橙' },
  { v: 'danger', l: '危险/红' },
  { v: 'info', l: '信息/灰' },
]
const itemRules: FormRules = {
  label: [{ required: true, message: '请输入显示文本', trigger: 'blur' }],
  value: [
    { required: true, message: '请输入字典值', trigger: 'blur' },
    {
      validator: (_r, v: string, cb) => {
        const dup = sys.dictItems.some(
          (i) => i.typeCode === currentTypeCode.value && i.value === v && i.id !== itemEditing.value?.id,
        )
        dup ? cb(new Error('同分类下字典值不能重复')) : cb()
      },
      trigger: 'blur',
    },
  ],
}

function openItemAdd() {
  if (!currentTypeCode.value) {
    ElMessage.warning('请先在左侧选择字典分类')
    return
  }
  itemEditing.value = null
  const maxSort = currentItems.value.reduce((m, i) => Math.max(m, i.sort), 0)
  Object.assign(itemForm, { label: '', value: '', sort: maxSort + 1, status: 'active', tagType: 'info', remark: '' })
  itemDialog.value = true
}
function openItemEdit(row: DictItem) {
  itemEditing.value = row
  Object.assign(itemForm, {
    label: row.label, value: row.value, sort: row.sort, status: row.status,
    tagType: row.tagType || 'info', remark: row.remark || '',
  })
  itemDialog.value = true
}
async function submitItem() {
  if (itemSaving.value) return
  await itemFormRef.value?.validate()
  const payload = {
    typeCode: currentTypeCode.value,
    label: itemForm.label,
    value: itemForm.value,
    sort: Number(itemForm.sort) || 0,
    status: itemForm.status,
    tagType: itemForm.tagType,
    remark: itemForm.remark,
  }
  itemSaving.value = true
  try {
    if (itemEditing.value) {
      await sys.updateItem(itemEditing.value.id, payload)
      ElMessage.success('字典项已更新')
    } else {
      await sys.addItem(payload)
      ElMessage.success('字典项已新增')
    }
    itemDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    itemSaving.value = false
  }
}
async function removeItem(row: DictItem) {
  try {
    await ElMessageBox.confirm(`确认删除字典项「${row.label}」？`, '删除字典项', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await sys.removeItem(row.id)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}
async function toggleItem(row: DictItem) {
  try {
    await sys.updateItem(row.id, { status: row.status === 'active' ? 'disabled' : 'active' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}
</script>

<template>
  <div class="dict-page">
    <el-row :gutter="16">
      <!-- 字典分类 -->
      <el-col :span="8">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-header">
              <span class="panel-title">字典分类（{{ filteredTypes.length }}）</span>
              <el-button v-if="can('sys:dict:type:add')" type="primary" size="small" :icon="'Plus'" @click="openTypeAdd">新增分类</el-button>
            </div>
          </template>
          <el-input v-model="typeKeyword" placeholder="搜索分类名称/编码" clearable size="small" style="margin-bottom:10px" />
          <div class="type-list">
            <div
              v-for="t in filteredTypes"
              :key="t.id"
              class="type-row"
              :class="{ active: t.code === currentTypeCode }"
              @click="selectType(t.code)"
            >
              <div class="type-main">
                <span class="type-name">{{ t.name }}</span>
                <span class="type-code">{{ t.code }}</span>
              </div>
              <el-tag v-if="t.status === 'disabled'" size="small" type="info">停用</el-tag>
              <div class="type-ops" @click.stop>
                <el-button v-if="can('sys:dict:type:edit')" link type="primary" size="small" @click="openTypeEdit(t)">编辑</el-button>
                <el-button v-if="can('sys:dict:type:delete')" link type="danger" size="small" @click="removeType(t)">删除</el-button>
              </div>
            </div>
            <el-empty v-if="filteredTypes.length === 0" description="暂无字典分类" :image-size="70" />
          </div>
        </el-card>
      </el-col>

      <!-- 字典项 -->
      <el-col :span="16">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-header">
              <span class="panel-title">
                字典项
                <span v-if="currentType" class="panel-sub">「{{ currentType.name }}」共 {{ currentItems.length }} 项</span>
              </span>
              <el-button
                v-if="can('sys:dict:item:add')"
                type="primary"
                size="small"
                :icon="'Plus'"
                :disabled="!currentTypeCode"
                @click="openItemAdd"
              >新增字典项</el-button>
            </div>
          </template>

          <el-input v-model="itemKeyword" placeholder="搜索文本/值" clearable size="small" style="margin-bottom:10px;width:260px" />
          <el-table :data="currentItems" border stripe size="default">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column prop="label" label="显示文本" min-width="120" />
            <el-table-column prop="value" label="字典值" min-width="120">
              <template #default="{ row }"><code class="val-code">{{ row.value }}</code></template>
            </el-table-column>
            <el-table-column prop="sort" label="排序" width="70" />
            <el-table-column label="标签样式" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.tagType || 'info'">样式</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
                  {{ row.status === 'active' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button v-if="can('sys:dict:item:edit')" link type="primary" size="small" @click="openItemEdit(row)">编辑</el-button>
                <el-button v-if="can('sys:dict:item:edit')" link type="warning" size="small" @click="toggleItem(row)">
                  {{ row.status === 'active' ? '停用' : '启用' }}
                </el-button>
                <el-button v-if="can('sys:dict:item:delete')" link type="danger" size="small" @click="removeItem(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty :description="currentTypeCode ? '该分类下暂无字典项' : '请先在左侧选择字典分类'" />
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 分类弹窗 -->
    <el-dialog v-model="typeDialog" :title="typeEditing ? '编辑字典分类' : '新增字典分类'" width="460px">
      <el-form ref="typeFormRef" :model="typeForm" :rules="typeRules" label-width="90px">
        <el-form-item label="类型编码" prop="code">
          <el-input v-model="typeForm.code" placeholder="如 user_status，保存后不可改" :disabled="!!typeEditing" />
        </el-form-item>
        <el-form-item label="类型名称" prop="name">
          <el-input v-model="typeForm.name" placeholder="如 用户状态" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="typeForm.status">
            <el-radio value="active">启用</el-radio>
            <el-radio value="disabled">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="typeForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="typeDialog = false">取消</el-button>
        <el-button type="primary" :loading="typeSaving" @click="submitType">保存</el-button>
      </template>
    </el-dialog>

    <!-- 字典项弹窗 -->
    <el-dialog v-model="itemDialog" :title="itemEditing ? '编辑字典项' : '新增字典项'" width="460px">
      <el-form ref="itemFormRef" :model="itemForm" :rules="itemRules" label-width="90px">
        <el-form-item label="显示文本" prop="label">
          <el-input v-model="itemForm.label" placeholder="如 启用" />
        </el-form-item>
        <el-form-item label="字典值" prop="value">
          <el-input v-model="itemForm.value" placeholder="如 active（保存后不可改）" :disabled="!!itemEditing" />
        </el-form-item>
        <el-form-item label="排序号">
          <el-input-number v-model="itemForm.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="标签样式">
          <el-select v-model="itemForm.tagType" style="width:160px">
            <el-option v-for="o in TAG_OPTIONS" :key="o.v" :label="o.l" :value="o.v" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="itemForm.status">
            <el-radio value="active">启用</el-radio>
            <el-radio value="disabled">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="itemForm.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="itemSaving" @click="submitItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.dict-page {
  display: flex;
  flex-direction: column;
}
.panel {
  min-height: 420px;
}
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}
.panel-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--iam-text-muted);
  margin-left: 8px;
}
.type-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 560px;
  overflow-y: auto;
}
.type-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--app-border-color);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition:
    border-color 0.2s,
    background-color 0.2s,
    box-shadow 0.2s;
  &:hover {
    border-color: var(--app-card-hover-border);
    background: var(--app-color-primary-light);
  }
  &.active {
    border-color: var(--iam-primary);
    background: var(--app-selected-bg);
  }
}
.type-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.type-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--iam-text-strong);
}
.type-code {
  font-size: 11px;
  color: var(--iam-text-muted);
  font-family: monospace;
}
.type-ops {
  display: none;
  flex-shrink: 0;
}
.type-row:hover .type-ops {
  display: inline-flex;
}
.val-code {
  font-family: monospace;
  color: var(--iam-primary);
  background: var(--el-color-primary-light-9);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
