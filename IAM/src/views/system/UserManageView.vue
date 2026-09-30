<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useIamStore } from '@/stores/iam'
import { useAuthStore } from '@/stores/auth'
import type { User } from '@/iam/types'
import { ORG_CASCADER_PROPS, type OrgNode } from '@/iam/org-tree'
import type { EffectivePermissionView, PermissionExplanation } from '@/iam/insight'

import { iamApi } from '@/api/iam'
import FabricIdentityPanel from './FabricIdentityPanel.vue'
import { identityLabel } from '@/iam/identity-display'
const orgTree = ref<OrgNode[]>([])
const identityUser = ref('')
const creationKey = ref('')
const iam = useIamStore()
const auth = useAuthStore()

/** 仅启用状态的角色可被分配给用户（停用角色不参与新分配） */
const activeRoles = computed(() => iam.roles.filter((r) => r.status === 'active'))
/** 级联选择器配置 */
const orgProps = ORG_CASCADER_PROPS

onMounted(() => {
  void reload()
})

async function reload() {
  try {
    await Promise.all([iam.fetchUsers(), iam.fetchRoles(), iamApi.orgTree().then(v => { orgTree.value = v })])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '数据加载失败')
  }
}

const search = reactive({
  keyword: '',
  status: '',
  roleId: '',
})

const filteredUsers = computed(() => {
  return iam.users.filter((u) => {
    if (search.keyword) {
      const kw = search.keyword.toLowerCase()
      const hay = [u.username, u.name, u.phone, u.zone, u.company, u.dept, u.group, u.orgPath]
        .filter(Boolean).join(' ').toLowerCase()
      if (!hay.includes(kw)) return false
    }
    if (search.status && u.status !== search.status) return false
    if (search.roleId && !u.roleIds.includes(search.roleId)) return false
    return true
  })
})

// ---- 新增/编辑弹窗 ----
const dialogVisible = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive<Partial<User>>({
  username: '',
  name: '',
  phone: '',
  email: '',
  password: '',
  roleIds: [],
  orgCodes: [],
  status: 'active',
  blockchainId: '',
  blockchainAddress: '',
})

// 密码规则随模式变化：新增必填，编辑时留空表示不修改（仅校验长度）
const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password:
    dialogMode.value === 'add'
      ? [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, message: '密码至少 6 位', trigger: 'blur' },
        ]
      : [{ min: 6, message: '密码至少 6 位', trigger: 'blur' }],
  roleIds: [{ type: 'array', required: true, message: '请选择角色', trigger: 'change' }],
  orgCodes: [
    {
      type: 'array',
      required: true,
      validator: (_rule, value: string[], cb: (e?: Error) => void) => {
        if (!value || value.length === 0) return cb(new Error('请选择所属区域/公司/部门/组'))
        cb()
      },
      trigger: 'change',
    },
  ],
  phone: [
    {
      validator: (_rule, value: string, cb: (e?: Error) => void) => {
        if (!value) return cb()
        if (!/^1[3-9]\d{9}$/.test(value)) return cb(new Error('手机号格式不正确'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  email: [
    {
      validator: (_rule, value: string, cb: (e?: Error) => void) => {
        if (!value) return cb()
        if (!/^[\w.+-]+@[\w-]+(\.[\w-]+)+$/.test(value)) return cb(new Error('邮箱格式不正确'))
        cb()
      },
      trigger: 'blur',
    },
  ],
}))

/** 是否正在编辑当前登录用户（此时禁止停用/清空角色，防止自锁） */
const isEditingSelf = computed(() => form.id === auth.currentUser?.id)

function openAdd() {
  creationKey.value = crypto.randomUUID()
  dialogMode.value = 'add'
  Object.assign(form, {
    username: '', name: '', phone: '', email: '', password: '',
    roleIds: [], orgCodes: [], status: 'active', blockchainId: '', blockchainAddress: '',
  })
  dialogVisible.value = true
}

function openEdit(user: User) {
  dialogMode.value = 'edit'
  Object.assign(form, { ...user, password: '', orgCodes: user.orgCodes ? [...user.orgCodes] : [], roleIds: [...user.roleIds] })
  dialogVisible.value = true
}

async function handleSubmit() {
  if (!formRef.value || saving.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid || saving.value) return
    saving.value = true
    try {
      if (dialogMode.value === 'add') {
        const result = await iam.addUser(form as Omit<User, 'id' | 'createdAt' | 'updatedAt'>, creationKey.value)
        if (result.success) {
          if (result.pending) ElMessage.info('用户记录已保存，身份仍在处理中；请在身份详情中查询任务')
          else ElMessage.success('用户及 Fabric 身份创建成功')
          dialogVisible.value = false
        } else {
          ElMessage.error(result.message || '创建失败')
        }
      } else {
        const updateData: Partial<User> = { ...form }
        if (!form.password) delete updateData.password
        const result = await iam.updateUser(form.id!, updateData)
        if (result.success) {
          ElMessage.success('用户更新成功')
          dialogVisible.value = false
        } else {
          ElMessage.error(result.message || '更新失败')
        }
      }
    } finally {
      saving.value = false
    }
  })
}

async function handleDelete(user: User) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${user.name}（${user.username}）」吗？此操作不可恢复。`, '删除确认', { type: 'warning' })
    const result = await iam.deleteUser(user.id)
    if (result.success) ElMessage.success('删除成功')
    else ElMessage.error(result.message || '删除失败')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function handleToggleStatus(user: User) {
  const result = await iam.toggleUserStatus(user.id)
  if (result.success) ElMessage.success(user.status === 'active' ? '已停用' : '已启用')
  else ElMessage.error(result.message || '操作失败')
}

// ---- 重置密码 ----
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdForm = reactive({ userId: '', username: '', password: '', confirm: '' })
function openResetPwd(user: User) {
  pwdForm.userId = user.id
  pwdForm.username = user.username
  pwdForm.password = ''
  pwdForm.confirm = ''
  pwdVisible.value = true
}
async function handleResetPwd() {
  if (pwdSaving.value) return
  if (pwdForm.password.length < 6) { ElMessage.error('密码至少 6 位'); return }
  if (pwdForm.password !== pwdForm.confirm) { ElMessage.error('两次密码不一致'); return }
  pwdSaving.value = true
  try {
    const result = await iam.resetPassword(pwdForm.userId, pwdForm.password)
    if (result.success) { ElMessage.success('密码重置成功'); pwdVisible.value = false }
    else ElMessage.error(result.message || '重置失败')
  } finally {
    pwdSaving.value = false
  }
}

// ---- 分配角色 ----
const roleVisible = ref(false)
const roleSaving = ref(false)
const roleForm = reactive({ userId: '', username: '', roleIds: [] as string[] })
function openAssignRole(user: User) {
  roleForm.userId = user.id
  roleForm.username = user.username
  roleForm.roleIds = [...user.roleIds]
  roleVisible.value = true
}
async function handleAssignRole() {
  if (roleSaving.value) return
  roleSaving.value = true
  try {
    const result = await iam.assignUserRoles(roleForm.userId, roleForm.roleIds)
    if (result.success) { ElMessage.success('角色分配成功'); roleVisible.value = false }
    else ElMessage.error(result.message || '分配失败')
  } finally {
    roleSaving.value = false
  }
}

const canAdd = computed(() => auth.permCodes.has('iam:user:add:add'))
const canEdit = computed(() => auth.permCodes.has('iam:user:edit:edit'))
const canDelete = computed(() => auth.permCodes.has('iam:user:delete:delete'))
const canToggle = computed(() =>
  ['iam:user:delete:delete', 'iam:user:delete:execute'].some((c) => auth.permCodes.has(c)),
)
const canAssignRole = computed(() =>
  ['iam:user:role:edit', 'iam:user:role:execute'].some((c) => auth.permCodes.has(c)),
)
const canResetPwd = computed(() =>
  ['iam:auth:password:edit', 'iam:auth:password:execute'].some((c) => auth.permCodes.has(c)),
)

// ---- 有效权限 / 来源解释 ----
const effVisible = ref(false)
const effLoading = ref(false)
const effView = ref<EffectivePermissionView | null>(null)
const queryCode = ref('')
const explanation = ref<PermissionExplanation | null>(null)
const explainLoading = ref(false)

async function openEffective(user: User) {
  effVisible.value = true
  effLoading.value = true
  effView.value = null
  queryCode.value = ''
  explanation.value = null
  try {
    effView.value = await iamApi.effectivePermissions(user.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '有效权限加载失败')
  } finally {
    effLoading.value = false
  }
}

async function handleExplain() {
  if (!effView.value || !queryCode.value.trim()) return
  explainLoading.value = true
  try {
    explanation.value = await iamApi.explainPermission(effView.value.userId, queryCode.value.trim())
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解释失败')
  } finally {
    explainLoading.value = false
  }
}
</script>

<template>
  <div class="user-manage">
    <!-- 搜索栏 -->
    <el-card class="search-card" shadow="never">
      <el-form :inline="true" :model="search">
        <el-form-item label="关键词">
          <el-input v-model="search.keyword" placeholder="用户名/姓名/手机号/组织" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="search.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" value="active" />
            <el-option label="停用" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="search.roleId" placeholder="全部" clearable style="width: 160px">
            <el-option v-for="r in iam.roles" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="reload()">查询</el-button>
          <el-button @click="search.keyword=''; search.status=''; search.roleId=''">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 操作栏 + 表格 -->
    <el-card class="table-card" shadow="never">
      <div class="table-toolbar">
        <span class="table-title">用户列表（{{ filteredUsers.length }}）</span>
        <el-button v-if="canAdd" type="primary" @click="openAdd">
          <el-icon><Plus /></el-icon>新增用户
        </el-button>
      </div>

      <el-table :data="filteredUsers" stripe style="width: 100%" v-loading="iam.loading">
        <el-table-column prop="username" label="用户名" width="140" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="所属组织" min-width="210">
          <template #default="{ row }">
            <el-tooltip v-if="row.orgPath" :content="`${row.zone} / ${row.company} / ${row.dept} / ${row.group}`" placement="top">
              <div class="org-cell">
                <div class="org-main">{{ row.dept }}<span v-if="row.group"> / {{ row.group }}</span></div>
                <div class="org-sub">{{ row.zone }} · {{ row.company }}</div>
              </div>
            </el-tooltip>
            <span v-else style="color:var(--el-text-color-placeholder)">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="160">
          <template #default="{ row }">
            <el-tag v-for="rid in row.roleIds" :key="rid" size="small" style="margin-right:4px">
              {{ iam.roles.find((r) => r.id === rid)?.name || rid }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Fabric 身份" width="180">
          <template #default="{ row }">
            <el-button link type="primary" @click="identityUser = row.id">{{ identityLabel(row.fabricIdentity?.state || 'NOT_PROVISIONED') }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
              {{ row.status === 'active' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" width="160">
          <template #default="{ row }">
            {{ row.lastLoginAt ? new Date(row.lastLoginAt).toLocaleString('zh-CN') : '从未登录' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEffective(row)">有效权限</el-button>
            <el-button v-if="canEdit" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canAssignRole" link type="primary" size="small" @click="openAssignRole(row)">分配角色</el-button>
            <el-button v-if="canResetPwd" link type="warning" size="small" @click="openResetPwd(row)">重置密码</el-button>
            <el-button v-if="canToggle && row.id !== auth.currentUser?.id" link :type="row.status === 'active' ? 'danger' : 'success'" size="small" @click="handleToggleStatus(row)">
              {{ row.status === 'active' ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canDelete && row.id !== auth.currentUser?.id" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog :model-value="!!identityUser" title="Fabric 身份详情" width="min(900px, 96vw)" @close="identityUser = ''">
      <FabricIdentityPanel v-if="identityUser" :user-id="identityUser" />
    </el-dialog>

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'add' ? '新增用户' : '编辑用户'" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" :disabled="dialogMode === 'edit'" placeholder="登录用户名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="姓名" prop="name">
              <el-input v-model="form.name" placeholder="真实姓名" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="dialogMode === 'add' ? '密码' : '新密码'" prop="password">
              <el-input v-model="form.password" type="password" show-password :placeholder="dialogMode === 'edit' ? '不修改请留空' : '至少6位'" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="手机号" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="form.email" placeholder="邮箱" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width:100%" :disabled="isEditingSelf">
                <el-option label="启用" value="active" />
                <el-option label="停用" value="disabled" />
              </el-select>
              <div v-if="isEditingSelf" style="font-size:11px;color:var(--app-color-warning);line-height:1.4">不能停用当前登录账号</div>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="所属组织" prop="orgCodes">
              <el-cascader
                v-model="form.orgCodes"
                :options="orgTree"
                :props="orgProps"
                placeholder="请依次选择 区域 / 公司 / 部门 / 组（列表选择，不可手动输入）"
                filterable
                clearable
                style="width:100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="分配角色" prop="roleIds">
              <el-select v-model="form.roleIds" multiple placeholder="选择角色（可多选，仅显示启用角色）" style="width:100%">
                <el-option v-for="r in activeRoles" :key="r.id" :label="r.name" :value="r.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-alert title="保存用户后自动供给 Fabric 身份，处理结果可在身份详情中查询。" type="info" :closable="false" />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="400px">
      <p style="color:var(--iam-text-muted);margin-bottom:16px">为用户「{{ pwdForm.username }}」重置密码</p>
      <el-form label-width="80px">
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="至少6位" />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再次输入" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="handleResetPwd">确定重置</el-button>
      </template>
    </el-dialog>

    <!-- 分配角色弹窗 -->
    <el-dialog v-model="roleVisible" title="分配角色" width="480px">
      <p style="color:var(--iam-text-muted);margin-bottom:16px">为用户「{{ roleForm.username }}」分配角色（可多选，权限取并集）</p>
      <el-checkbox-group v-model="roleForm.roleIds">
        <el-checkbox v-for="r in activeRoles" :key="r.id" :label="r.id" style="display:block;margin:8px 0">
          <span style="font-weight:500">{{ r.name }}</span>
          <span style="color:var(--iam-text-muted);font-size:12px;margin-left:8px">{{ r.permCodes.length }} 项权限</span>
        </el-checkbox>
        <div v-if="activeRoles.length === 0" style="color:var(--iam-text-muted)">暂无可分配的启用角色</div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="roleSaving" @click="handleAssignRole">确定</el-button>
      </template>
    </el-dialog>

    <!-- 有效权限与来源抽屉 -->
    <el-drawer v-model="effVisible" title="有效权限与来源" size="min(600px, 100vw)" direction="rtl">
      <div v-loading="effLoading" class="eff-box">
        <template v-if="effView">
          <el-alert v-if="!effView.enabled" title="用户已停用，所有权限均不生效" type="warning" :closable="false" />
          <el-alert v-else-if="effView.superAdmin" title="超级管理员具有全部接口权限；下方列出已登记权限" type="info" :closable="false" />
          <div class="eff-user">
            <span class="eff-username">{{ effView.displayName }}（{{ effView.username }}）</span>
          </div>

          <div class="eff-section-title">有效角色（{{ effView.roles.length }}）</div>
          <div class="eff-roles">
            <el-tag v-for="r in effView.roles" :key="r.id" size="small" class="eff-tag">
              {{ r.name }}（{{ r.code }}）
            </el-tag>
            <span v-if="effView.roles.length === 0" class="eff-empty">无有效角色</span>
          </div>

          <el-divider />

          <div class="eff-section-title">权限解释</div>
          <div class="eff-query">
            <el-input
              v-model="queryCode"
              placeholder="输入权限编码，如 sys:config:list:view"
              clearable
              @keyup.enter="handleExplain"
            />
            <el-button type="primary" :loading="explainLoading" @click="handleExplain">查询</el-button>
          </div>
          <div v-if="explanation" class="eff-explain">
            <el-tag :type="explanation.owned ? 'success' : 'info'" size="small" effect="dark">
              {{ explanation.owned ? '拥有' : '未拥有' }}
            </el-tag>
            <template v-if="explanation.owned">
              <span class="eff-explain-text">来源角色：</span>
              <el-tag v-for="r in explanation.sourceRoles" :key="r.id" size="small" class="eff-tag">
                {{ r.code }}
              </el-tag>
            </template>
            <span v-else class="eff-explain-text">{{ explanation.reason }}</span>
          </div>

          <el-divider />

          <div class="eff-section-title">最终有效权限（{{ effView.permissions.length }}）</div>
          <el-scrollbar max-height="340px">
            <div v-for="p in effView.permissions" :key="p.permissionCode" class="eff-perm">
              <div class="eff-perm-head">
                <span class="eff-perm-code">{{ p.permissionCode }}</span>
                <span class="eff-perm-name">{{ p.permissionName }}</span>
              </div>
              <div class="eff-perm-sources">
                <span class="eff-from-label">来源：</span>
                <el-tag v-for="r in p.sourceRoles" :key="r.id" size="small" type="info" class="eff-tag">
                  {{ r.code }}
                </el-tag>
              </div>
            </div>
            <div v-if="effView.permissions.length === 0" class="eff-empty">该用户无有效权限</div>
          </el-scrollbar>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.user-manage {
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

.bc-id {
  font-family: monospace;
  font-size: 12px;
  color: var(--iam-primary);
  background: var(--el-color-primary-light-9);
  padding: 2px 6px;
  border-radius: 4px;
}

.org-cell {
  line-height: 1.35;
  cursor: default;
}

.org-main {
  font-size: 13px;
  color: var(--iam-text-strong);
}

.org-sub {
  font-size: 11px;
  color: var(--iam-text-muted);
}

/* ============ 有效权限抽屉 ============ */
.eff-box {
  padding: 0 20px 20px;
}

.eff-user {
  margin-bottom: 14px;
}

.eff-username {
  font-size: 16px;
  font-weight: 600;
  color: var(--iam-text-strong);
}

.eff-section-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--iam-text-strong);
  margin-bottom: 10px;
}

.eff-tag {
  margin: 0 8px 8px 0;
}

.eff-empty {
  font-size: 13px;
  color: var(--iam-text-muted);
}

.eff-query {
  display: flex;
  gap: 10px;
}

.eff-explain {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
  padding: 10px 12px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}

.eff-explain-text {
  font-size: 13px;
  color: var(--iam-text-base);
}

.eff-perm {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  margin-bottom: 8px;
}

.eff-perm-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}

.eff-perm-code {
  font-family: monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--iam-primary);
}

.eff-perm-name {
  font-size: 12px;
  color: var(--iam-text-muted);
}

.eff-perm-sources {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.eff-from-label {
  font-size: 12px;
  color: var(--iam-text-muted);
  margin-right: 6px;
}
</style>
