<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { ElMessage, type FormInstance } from 'element-plus'
import { iamApi, type NavigationMenuRow, type NavigationMenuUpdatePayload } from '@/api/iam'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const rows = ref<NavigationMenuRow[]>([])
const loading = ref(false)
const dialog = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const editing = ref<NavigationMenuRow | null>(null)
const appFilter = ref('')
const form = reactive({
  menuName: '',
  projectedDisplayTitle: '',
  entryPath: '',
  routePath: '',
  visible: 1,
  enabled: 1,
  navEligible: 1,
  featureStatus: 'PARTIAL',
  unavailableReason: '',
})

const appCodes = computed(() => [...new Set(rows.value.map(r => r.appCode))].sort())
const filteredRows = computed(() => appFilter.value ? rows.value.filter(r => r.appCode === appFilter.value) : rows.value)
const canEdit = computed(() => auth.isSuperAdmin || auth.permCodes.has('iam:menu:sync:execute'))

async function load() {
  loading.value = true
  try {
    rows.value = await iamApi.navigationMenus()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导航菜单加载失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

function openEdit(row: NavigationMenuRow) {
  editing.value = row
  Object.assign(form, {
    menuName: row.menuName || '',
    projectedDisplayTitle: row.projectedDisplayTitle || '',
    entryPath: row.entryPath || '',
    routePath: row.routePath || '',
    visible: Number(row.visible),
    enabled: Number(row.enabled),
    navEligible: Number(row.navEligible),
    featureStatus: row.featureStatus || 'PARTIAL',
    unavailableReason: row.unavailableReason || '',
  })
  dialog.value = true
}

async function save() {
  if (!editing.value) return
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload: NavigationMenuUpdatePayload = {
      menuName: form.menuName,
      projectedDisplayTitle: form.projectedDisplayTitle,
      entryPath: form.entryPath,
      routePath: form.routePath,
      visible: form.visible,
      enabled: form.enabled,
      navEligible: form.navEligible,
      featureStatus: form.featureStatus,
      unavailableReason: form.unavailableReason,
    }
    await iamApi.updateNavigationMenu(editing.value.stableMenuKey, payload)
    ElMessage.success('导航菜单已更新')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="nav-admin">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div>
            <div class="title">导航菜单维护</div>
            <div class="subtitle">维护展示标题、入口路径和发布状态；层级与权限映射不在本页修改。</div>
          </div>
          <div class="actions">
            <el-select v-model="appFilter" clearable placeholder="全部系统" size="small" style="width:150px">
              <el-option v-for="code in appCodes" :key="code" :label="code" :value="code" />
            </el-select>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table v-loading="loading" :data="filteredRows" border stripe size="small">
        <el-table-column prop="appCode" label="系统" width="80" />
        <el-table-column prop="stableMenuKey" label="稳定菜单键" min-width="170" />
        <el-table-column prop="menuName" label="菜单名" min-width="120" />
        <el-table-column prop="projectedDisplayTitle" label="展示标题" min-width="120" />
        <el-table-column prop="menuKind" label="类型" width="80" />
        <el-table-column prop="entryPath" label="入口路径" min-width="180" />
        <el-table-column prop="featureStatus" label="发布状态" width="120" />
        <el-table-column label="可见/启用/可导航" width="150">
          <template #default="{ row }">
            {{ row.visible }} / {{ row.enabled }} / {{ row.navEligible }}
          </template>
        </el-table-column>
        <el-table-column v-if="canEdit" label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" title="编辑导航菜单" width="560px">
      <el-form ref="formRef" :model="form" label-width="100px">
        <el-form-item label="菜单名">
          <el-input v-model="form.menuName" />
        </el-form-item>
        <el-form-item label="展示标题">
          <el-input v-model="form.projectedDisplayTitle" />
        </el-form-item>
        <el-form-item label="入口路径">
          <el-input v-model="form.entryPath" />
        </el-form-item>
        <el-form-item label="路由路径">
          <el-input v-model="form.routePath" />
        </el-form-item>
        <el-form-item label="发布状态">
          <el-select v-model="form.featureStatus" style="width:100%">
            <el-option label="READY" value="READY" />
            <el-option label="PARTIAL" value="PARTIAL" />
            <el-option label="PENDING_VERIFICATION" value="PENDING_VERIFICATION" />
            <el-option label="PLANNED" value="PLANNED" />
          </el-select>
        </el-form-item>
        <el-form-item label="不可用原因">
          <el-input v-model="form.unavailableReason" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="可见">
          <el-switch v-model="form.visible" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="可导航">
          <el-switch v-model="form.navEligible" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.nav-admin { display: flex; flex-direction: column; gap: 16px; }
.header { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.title { font-size: 16px; font-weight: 600; }
.subtitle { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.actions { display: flex; gap: 8px; align-items: center; }
</style>
