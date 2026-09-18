<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useIamStore } from '@/stores/iam'
import { useAuthStore } from '@/stores/auth'
import { MENU_TREE, ensureViewPerms, type MenuNode, type PermOp } from '@/iam/menu-tree'
import type { Role } from '@/iam/types'

const iam = useIamStore()
const auth = useAuthStore()

onMounted(() => {
  void reload()
})

async function reload() {
  try {
    await Promise.all([iam.fetchRoles(), iam.fetchUsers()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '数据加载失败')
  }
}

const search = reactive({ keyword: '', status: '' })
const filteredRoles = computed(() => {
  return iam.roles.filter((r) => {
    if (search.status && r.status !== search.status) return false
    if (search.keyword) {
      const kw = search.keyword.toLowerCase()
      if (!r.name.toLowerCase().includes(kw) && !r.code.toLowerCase().includes(kw)) return false
    }
    return true
  })
})

function getUserCount(roleId: string) {
  return iam.users.filter((u) => u.roleIds.includes(roleId)).length
}

// ---- 新增/编辑弹窗 ----
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formRef = ref<FormInstance>()
const form = reactive<Partial<Role>>({
  name: '',
  code: '',
  description: '',
  permCodes: [],
  status: 'active',
})

const rules = computed<FormRules>(() => ({
  name: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_]*$/,
      message: '编码需为小写字母开头，仅含小写字母/数字/下划线',
      trigger: 'blur',
    },
  ],
}))

function openAdd() {
  dialogMode.value = 'add'
  Object.assign(form, { name: '', code: '', description: '', permCodes: [], status: 'active' })
  dialogVisible.value = true
}

function openEdit(role: Role) {
  dialogMode.value = 'edit'
  Object.assign(form, { ...role, permCodes: [...role.permCodes] })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    if (dialogMode.value === 'add') {
      const result = await iam.addRole(form as Omit<Role, 'id' | 'createdAt' | 'updatedAt'>)
      if (result.success) { ElMessage.success('角色创建成功'); dialogVisible.value = false }
      else ElMessage.error(result.message || '创建失败')
    } else {
      const result = await iam.updateRole(form.id!, form)
      if (result.success) { ElMessage.success('角色更新成功'); dialogVisible.value = false }
      else ElMessage.error(result.message || '更新失败')
    }
  })
}

async function handleDelete(role: Role) {
  try {
    await ElMessageBox.confirm(
      `确定删除角色「${role.name}」吗？${getUserCount(role.id) > 0 ? '该角色仍被用户使用，无法删除。' : '删除后不可恢复。'}`,
      '删除确认',
      { type: 'warning' },
    )
    const result = await iam.deleteRole(role.id)
    if (result.success) ElMessage.success('删除成功')
    else ElMessage.error(result.message || '删除失败')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function handleToggleStatus(role: Role) {
  const result = await iam.toggleRoleStatus(role.id)
  if (result.success) ElMessage.success(role.status === 'active' ? '已停用' : '已启用')
  else ElMessage.error(result.message || '操作失败')
}

// ---- 权限分配弹窗 ----
const permVisible = ref(false)
const permForm = reactive({ roleId: '', roleName: '', checkedPerms: [] as string[] })
const permTreeRef = ref()

/**
 * 将 MENU_TREE 转为 el-tree 权限树结构。
 * 叶子节点为具体权限编码（view/add/edit/delete/execute/export/import/approve）。
 */
interface PermTreeNode {
  id: string
  label: string
  disabled?: boolean
  children?: PermTreeNode[]
}

const OP_LABELS: Record<PermOp, string> = {
  view: '查看',
  add: '新增',
  edit: '编辑',
  delete: '删除',
  execute: '执行',
  export: '导出',
  import: '导入',
  approve: '审批',
}

function buildPermTree(): PermTreeNode[] {
  const opOrder: PermOp[] = ['view', 'add', 'edit', 'delete', 'execute', 'approve', 'import', 'export']
  const walk = (node: MenuNode): PermTreeNode => {
    const children: PermTreeNode[] = []
    if (node.perms) {
      for (const op of opOrder) {
        const code = node.perms[op]
        if (code) {
          children.push({ id: code, label: OP_LABELS[op] })
        }
      }
    }
    if (node.children) {
      for (const child of node.children) {
        children.push(walk(child))
      }
    }
    return {
      id: node.id,
      label: node.name,
      disabled: !node.perms && (!node.children || node.children.length === 0),
      children: children.length > 0 ? children : undefined,
    }
  }
  return MENU_TREE.map(walk)
}

const permTreeData = computed(() => buildPermTree())

function openPermAssign(role: Role) {
  permForm.roleId = role.id
  permForm.roleName = role.name
  permForm.checkedPerms = [...role.permCodes]
  permVisible.value = true
}

async function handlePermSubmit() {
  // 获取所有选中的叶子节点（权限编码）
  const checked = permTreeRef.value?.getCheckedKeys() as string[] || []
  const halfChecked = permTreeRef.value?.getHalfCheckedKeys() as string[] || []
  const leafCodes = [...checked, ...halfChecked].filter((c) => c.includes(':'))
  // 自动补全查看权限：勾选操作权限时连带其 view，避免孤立授权
  const allChecked = ensureViewPerms(leafCodes)
  const result = await iam.assignRolePerms(permForm.roleId, allChecked)
  if (result.success) {
    ElMessage.success(`已分配 ${allChecked.length} 项权限（已自动补全查看权限）`)
    permVisible.value = false
  } else {
    ElMessage.error(result.message || '分配失败')
  }
}

const totalPermCount = computed(() => {
  let count = 0
  const walk = (nodes: MenuNode[]) => {
    for (const n of nodes) {
      if (n.perms) count += Object.keys(n.perms).length
      if (n.children) walk(n.children)
    }
  }
  walk(MENU_TREE)
  return count
})

const canAdd = computed(() => auth.permCodes.has('iam:role:add:add'))
const canEdit = computed(() => auth.permCodes.has('iam:role:edit:edit'))
const canDelete = computed(() => auth.permCodes.has('iam:role:delete:delete'))
const canToggle = computed(() => auth.permCodes.has('iam:role:edit:edit'))
const canAssignPerm = computed(() =>
  ['iam:role:perm:edit', 'iam:role:perm:execute'].some((c) => auth.permCodes.has(c)),
)
</script>

<template>
  <div class="role-manage">
    <el-card class="search-card" shadow="never">
      <el-form :inline="true">
        <el-form-item label="关键词">
          <el-input v-model="search.keyword" placeholder="角色名称/编码" clearable style="width:200px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="search.status" placeholder="全部" clearable style="width:120px">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload()">查询</el-button>
          <el-button @click="search.keyword=''; search.status=''">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <div class="table-toolbar">
        <span class="table-title">角色列表（{{ filteredRoles.length }}）</span>
        <el-button v-if="canAdd" type="primary" @click="openAdd">
          <el-icon><Plus /></el-icon>新增角色
        </el-button>
      </div>

      <el-table :data="filteredRoles" stripe style="width:100%" v-loading="iam.loading">
        <el-table-column prop="name" label="角色名称" width="140" />
        <el-table-column prop="code" label="角色编码" width="160">
          <template #default="{ row }">
            <code class="role-code">{{ row.code }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
        <el-table-column label="权限数" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.permCodes.length > 100 ? 'danger' : 'primary'">
              {{ row.permCodes.length }} / {{ totalPermCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="使用用户数" width="100" align="center">
          <template #default="{ row }">{{ getUserCount(row.id) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canAssignPerm && row.code !== 'super_admin'" link type="primary" size="small" @click="openPermAssign(row)">分配权限</el-button>
            <el-tag v-if="row.code === 'super_admin'" type="danger" size="small" effect="plain">内置全权限</el-tag>
            <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button
              v-if="canToggle && row.code !== 'super_admin'"
              link
              :type="row.status === 'active' ? 'warning' : 'success'"
              size="small"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 'active' ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canDelete && row.code !== 'super_admin'" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'add' ? '新增角色' : '编辑角色'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" placeholder="如：调度管理员" />
        </el-form-item>
        <el-form-item label="角色编码" prop="code">
          <el-input v-model="form.code" :disabled="dialogMode === 'edit'" placeholder="如：dispatcher（英文下划线）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="角色职责说明" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width:100%" :disabled="form.code === 'super_admin'">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="disabled" />
          </el-select>
          <div v-if="form.code === 'super_admin'" style="font-size:11px;color:var(--app-color-warning);line-height:1.4">内置系统管理员角色不可停用</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 权限分配弹窗（destroy-on-close 保证每次打开都按当前角色重新回显勾选） -->
    <el-dialog v-model="permVisible" :title="`分配权限 — ${permForm.roleName}`" width="720px" top="5vh" destroy-on-close>
      <div class="perm-tip">
        <el-alert type="info" :closable="false" show-icon>
          按「系统 → 模块 → 功能点 → 操作」层级勾选。勾选父节点会自动选中其下所有操作权限；
          也可单独勾选某个操作（如只给查看不给编辑）。勾选任意操作权限会自动连带其"查看"权限。
          当前共 {{ totalPermCount }} 项权限。
        </el-alert>
      </div>
      <div class="perm-tree-wrap">
        <el-tree
          ref="permTreeRef"
          :data="permTreeData"
          show-checkbox
          node-key="id"
          :default-checked-keys="permForm.checkedPerms"
          :props="{ label: 'label', children: 'children', disabled: 'disabled' }"
          default-expand-all
          class="perm-tree"
        />
      </div>
      <template #footer>
        <el-button @click="permVisible = false">取消</el-button>
        <el-button type="primary" @click="handlePermSubmit">保存权限</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.role-manage {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.search-card {
  :deep(.el-card__body) { padding: 16px 20px 0; }
}

.table-card {
  :deep(.el-card__body) { padding: 16px 20px; }
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.table-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--iam-text-strong);
}

.role-code {
  font-family: monospace;
  font-size: 12px;
  color: var(--iam-primary);
  background: var(--el-color-primary-light-9);
  padding: 2px 6px;
  border-radius: 4px;
}

.perm-tip {
  margin-bottom: 16px;
}

.perm-tree-wrap {
  max-height: 60vh;
  overflow: auto;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--el-fill-color-light);
}

.perm-tree {
  background: transparent;

  :deep(.el-tree-node__content) {
    height: 30px;
  }

  :deep(.el-tree-node__label) {
    font-size: 13px;
  }

  :deep(.is-disabled > .el-tree-node__content > .el-tree-node__label) {
    font-weight: 600;
    color: var(--iam-text-strong);
  }
}
</style>
