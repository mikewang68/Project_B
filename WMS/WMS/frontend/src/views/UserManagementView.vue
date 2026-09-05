<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listRoles, listUsers, saveUser, type RoleOption, type SaveUserRequest, type UserView } from '@/api/users'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const editingId = ref<number | null>(null)
const users = ref<UserView[]>([])
const roles = ref<RoleOption[]>([])
const form = reactive<SaveUserRequest>({ username: '', mobile: '', displayName: '', password: '', status: 'ENABLED', loginEnabled: true, roleCodes: ['NORMAL'], warehouseCodes: ['default'], ownerCodes: ['default'] })

async function load(): Promise<void> {
  loading.value = true
  try { [users.value, roles.value] = await Promise.all([listUsers(), listRoles()]) }
  catch (reason) { ElMessage.error(reason instanceof ApiError ? reason.message : '用户数据加载失败') }
  finally { loading.value = false }
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, { username: '', mobile: '', displayName: '', password: '', status: 'ENABLED', loginEnabled: true, roleCodes: ['NORMAL'], warehouseCodes: [auth.user?.tenant.currentWarehouse.code ?? 'default'], ownerCodes: [auth.user?.tenant.currentOwner.code ?? 'default'] })
  dialog.value = true
}

function openEdit(row: UserView): void {
  editingId.value = row.id
  Object.assign(form, { username: row.username, mobile: row.mobile ?? '', displayName: row.displayName, password: '', status: row.status, loginEnabled: row.loginEnabled, roleCodes: [...row.roles], warehouseCodes: [...row.warehouses], ownerCodes: [...row.owners] })
  dialog.value = true
}

async function submit(): Promise<void> {
  if (!form.username.trim() || !form.displayName.trim()) return void ElMessage.warning('请填写用户名和姓名')
  if (!editingId.value && (!form.password || form.password.length < 8)) return void ElMessage.warning('新建用户密码至少8位')
  saving.value = true
  try { await saveUser(editingId.value, form); ElMessage.success('用户已保存'); dialog.value = false; await load() }
  catch (reason) { ElMessage.error(reason instanceof ApiError ? reason.message : '保存失败') }
  finally { saving.value = false }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <header class="page-heading"><div><p class="eyebrow">SYSTEM</p><h1>用户管理</h1><p>维护公司内账号、角色以及可访问的仓库和货主。</p></div><el-button type="primary" @click="openCreate">新建用户</el-button></header>
    <section class="panel table-panel">
      <el-table v-loading="loading" :data="users" stripe>
        <el-table-column prop="username" label="账号" min-width="120" />
        <el-table-column prop="displayName" label="姓名" min-width="120" />
        <el-table-column prop="mobile" label="手机号" min-width="130"><template #default="scope">{{ scope.row.mobile || '-' }}</template></el-table-column>
        <el-table-column label="角色" min-width="160"><template #default="scope"><el-tag v-for="role in scope.row.roles" :key="role" class="role-tag">{{ role }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.status === 'ENABLED' && scope.row.loginEnabled ? 'success' : 'info'">{{ scope.row.status === 'ENABLED' && scope.row.loginEnabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="数据范围" min-width="180"><template #default="scope">{{ scope.row.warehouses.join('、') }} / {{ scope.row.owners.join('、') }}</template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button link type="primary" @click="openEdit(scope.row)">编辑</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialog" :title="editingId ? '编辑用户' : '新建用户'" width="620px">
      <el-form label-position="top" class="user-form">
        <div class="form-grid"><el-form-item label="账号"><el-input v-model="form.username" :disabled="editingId !== null" /></el-form-item><el-form-item label="姓名"><el-input v-model="form.displayName" /></el-form-item></div>
        <div class="form-grid"><el-form-item label="手机号"><el-input v-model="form.mobile" /></el-form-item><el-form-item :label="editingId ? '重置密码（不修改请留空）' : '初始密码'"><el-input v-model="form.password" type="password" show-password /></el-form-item></div>
        <el-form-item label="角色"><el-checkbox-group v-model="form.roleCodes"><el-checkbox v-for="role in roles" :key="role.code" :value="role.code">{{ role.name }}</el-checkbox></el-checkbox-group></el-form-item>
        <div class="form-grid"><el-form-item label="可访问仓库"><el-select v-model="form.warehouseCodes" multiple style="width:100%"><el-option v-for="item in auth.user?.tenant.warehouses" :key="item.id" :label="item.name" :value="item.code" /></el-select></el-form-item><el-form-item label="可访问货主"><el-select v-model="form.ownerCodes" multiple style="width:100%"><el-option v-for="item in auth.user?.tenant.owners" :key="item.id" :label="item.name" :value="item.code" /></el-select></el-form-item></div>
        <div class="form-grid"><el-form-item label="账号状态"><el-select v-model="form.status" style="width:100%"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item><el-form-item label="允许登录"><el-switch v-model="form.loginEnabled" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
    </el-dialog>
  </div>
</template>
