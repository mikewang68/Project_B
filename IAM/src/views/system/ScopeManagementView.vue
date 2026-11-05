<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { iamApi } from '@/api/iam'
import { scopeApi, type Overview, type ManagedUser, type ManagedRole, type RoleOption, type ScopeTriple, type SystemGrant, type MenuGrant, type Project, type Operation } from '@/api/scope'
import type { OrgNode } from '@/iam/org-tree'

const auth = useAuthStore()
const overview = ref<Overview | null>(null)
const people = ref<ManagedUser[]>([])
const roles = ref<ManagedRole[]>([])
const roleOptions = ref<RoleOption[]>([])
const organizations = ref<OrgNode[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const ctx = computed(() => overview.value?.userContext ?? auth.userContext)
const level = computed(() => ctx.value?.adminLevel)
const readonly = computed(() => ctx.value?.readOnly !== false)
const isL1 = computed(() => ['L1_SUPER','L1_READONLY'].includes(level.value ?? ''))
const isL3 = computed(() => level.value === 'L3_SUBSYSTEM')
const canConfigure = computed(() => ctx.value?.managementCapabilities.includes('CONFIGURE_PLATFORM') === true)
const names: Record<string,string> = { L1_SUPER: '全局管理员', L1_READONLY: '全局只读用户', L2_PROJECT: '项目管理员', L3_SUBSYSTEM: '子系统管理员', L4_OPERATOR: '业务操作员' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [state, users, roleRows, options] = await Promise.all([scopeApi.overview(), scopeApi.users(), scopeApi.roles(), scopeApi.roleOptions()])
    overview.value = state; people.value = users; roles.value = roleRows; roleOptions.value = options
    if(canConfigure.value) organizations.value = await iamApi.orgTree()
  } catch(e) { error.value = e instanceof Error ? e.message : '加载失败' }
  finally { loading.value = false }
}
async function perform(action: () => Promise<unknown>, message = '保存成功') {
  if(saving.value)return
  saving.value = true
  try { await action(); ElMessage.success(message); await auth.refreshCurrentUser(); await load(); return true }
  catch(e) { ElMessage.error(e instanceof Error ? e.message : '操作失败'); return false }
  finally { saving.value = false }
}
function version(project: string) { return overview.value?.projects.find(p => p.project_id === project)?.authz_version ?? -1 }
function tuple(row: SystemGrant | MenuGrant): ScopeTriple { return { projectId: row.project_id, regionId: row.region_id, subsystemCode: row.subsystem_code } }
function mayManage(row: ManagedUser) {
  return !readonly.value && row.id !== ctx.value?.userId && row.scope_state === 'V2_READY' && row.admin_level !== 'L1_SUPER'
    && (isL1.value || level.value === 'L2_PROJECT' && ['L3_SUBSYSTEM','L4_OPERATOR'].includes(row.admin_level ?? '') || isL3.value && row.admin_level === 'L4_OPERATOR')
}
async function changeStatus(row: ManagedUser) {
  await perform(() => scopeApi.userStatus(row.id, row.status === 'active' ? 'disabled' : 'active'), '账号状态已更新')
}
async function resetPassword(row: ManagedUser) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新密码，至少6位。', '重置 '+row.username+' 的密码', { inputType: 'password', inputPattern: /^.{6,}$/, inputErrorMessage: '密码至少6位' })
    await perform(() => scopeApi.resetPassword(row.id,value), '密码已重置，该用户需要重新登录')
  } catch { /* Cancel. */ }
}
async function deleteUser(row: ManagedUser) {
  try { await ElMessageBox.confirm('删除账号 '+row.username+'？历史记录将保留。','删除人员',{type:'warning'}); await perform(() => scopeApi.deleteUser(row.id),'账号已删除') }
  catch { /* Cancel. */ }
}

const userDialog = ref(false)
const userForm = reactive({ username:'', name:'', password:'', adminLevel:'L4_OPERATOR', readOnly:false, projectId:'', regionId:'', subsystemCode:'', roleIds:[] as string[], orgCodes:[] as string[] })
const userKey = ref('')
const checkState = ref<'unchecked'|'checking'|'available'|'occupied'|'error'>('unchecked')
const checkedName = ref('')
let checkVersion = 0
function invalidateName() { checkVersion++; checkState.value='unchecked'; checkedName.value='' }
async function checkUsername() {
  const value=userForm.username.trim()
  const generation=++checkVersion
  checkState.value='checking'
  try {
    const result=await scopeApi.availability(value)
    if(generation !== checkVersion || userForm.username.trim() !== value)return
    checkedName.value=result.normalized
    checkState.value=result.available?'available':'occupied'
  } catch { if(generation===checkVersion)checkState.value='error' }
}
function openUser() {
  Object.assign(userForm,{username:'',name:'',password:'',adminLevel:isL3.value?'L4_OPERATOR':isL1.value?'L2_PROJECT':'L3_SUBSYSTEM',readOnly:false,projectId:ctx.value?.projectId ?? '',regionId:ctx.value?.regionId ?? '',subsystemCode:ctx.value?.subsystemCode ?? '',roleIds:[],orgCodes:[]})
  invalidateName(); userKey.value=crypto.randomUUID(); userDialog.value=true
}
const userRegions = computed(() => overview.value?.regions.filter(r => r.project_id===userForm.projectId && r.status==='active') ?? [])
const userSystems = computed(() => overview.value?.systemGrants.filter(g => g.project_id===userForm.projectId && g.region_id===userForm.regionId && g.l1_allowed && g.l2_enabled) ?? [])
const canSubmitUser = computed(() => checkState.value==='available' && checkedName.value===userForm.username.trim().toLowerCase() && !!userForm.name.trim() && userForm.password.length>=6 && (userForm.adminLevel!=='L4_OPERATOR'||userForm.roleIds.length>0))
async function saveUser() {
  if(!canSubmitUser.value)return
  const body = { username:userForm.username.trim(), name:userForm.name.trim(), password:userForm.password, adminLevel:userForm.adminLevel,
    readOnly:userForm.adminLevel==='L1_READONLY'||userForm.readOnly, status:'active', roleIds:[...userForm.roleIds],
    ...(isL1.value?{orgCodes:userForm.orgCodes}:{}),
    ...(userForm.adminLevel==='L1_READONLY'?{}:userForm.adminLevel==='L2_PROJECT'?{projectId:userForm.projectId}:
      {projectId:userForm.projectId,regionId:userForm.regionId,subsystemCode:userForm.subsystemCode}) }
  const ok=await perform(async () => { const result=await scopeApi.createUser(body,userKey.value); if(result.fabricIdentity?.state!=='READY')ElMessage.info('账号已创建，身份供给任务仍在处理，可稍后查看。') }, '人员已创建')
  if(ok){userForm.password='';userDialog.value=false}
}

const roleDialog = ref(false)
const roleId = ref<string | null>(null)
const roleForm = reactive({ name:'',code:'',status:'active',projectId:'',actions:[] as string[] })
function openRole(row?: ManagedRole) {
  roleId.value=row?.id ?? null
  Object.assign(roleForm,{name:row?.role_name ?? '',code:row?.business_role_code ?? '',status:row?.status ?? 'active',projectId:row?.project_id ?? '',actions:(row?.operations ?? []).map(a=>JSON.stringify(a))})
  roleDialog.value=true
}
async function saveRole() {
  const ok=await perform(()=>scopeApi.saveRole(roleId.value,{name:roleForm.name,code:roleForm.code,status:roleForm.status,...(canConfigure.value&&roleForm.projectId?{projectId:roleForm.projectId}:{}),operations:roleForm.actions.map(a=>JSON.parse(a))}))
  if(ok)roleDialog.value=false
}
async function deleteRole(row: ManagedRole) {
  try { await ElMessageBox.confirm('删除岗位 '+row.role_name+'？关联人员将失去该岗位授权。','删除岗位',{type:'warning'}); await perform(()=>scopeApi.deleteRole(row.id),'岗位已删除') }
  catch { /* Cancel. */ }
}
const bindUser = ref<ManagedUser | null>(null)
const bindRoles = ref<string[]>([])
async function saveBindings() {
  const target=bindUser.value;if(!target)return
  if(await perform(()=>scopeApi.assignRoles(target.id,bindRoles.value),'岗位已分配'))bindUser.value=null
}

const projectDialog = ref(false)
const projectForm = reactive({ projectId:'',name:'',company:'',status:'active',expectedVersion:undefined as number | undefined })
function openProject(row?: Project) {
  Object.assign(projectForm,{projectId:row?.project_id ?? '',name:row?.project_name ?? '',company:row?.company_name ?? '',status:row?.status ?? 'active',expectedVersion:row?.authz_version})
  projectDialog.value=true
}
async function saveProject(){if(await perform(()=>scopeApi.project({...projectForm})))projectDialog.value=false}
const regionDialog = ref(false)
const regionForm = reactive({ projectId:'',regionId:'',name:'',type:'BUSINESS',status:'active' })
async function saveRegion(){if(await perform(()=>scopeApi.region({...regionForm,expectedVersion:version(regionForm.projectId)})))regionDialog.value=false}
const grantDialog = ref(false)
const grantForm = reactive({ projectId:'',regionId:'',subsystemCode:'' })
const grantRegions = computed(()=>overview.value?.regions.filter(r=>r.project_id===grantForm.projectId) ?? [])
async function saveGrant(){if(await perform(()=>scopeApi.systemGrant({scope:{...grantForm},allowed:true,enabled:false,expectedVersion:version(grantForm.projectId)})))grantDialog.value=false}
async function toggleSystem(row: SystemGrant) {
  await perform(()=>scopeApi.systemGrant({scope:tuple(row),allowed:isL1.value?!row.l1_allowed:row.l1_allowed,enabled:!row.l2_enabled,expectedVersion:version(row.project_id)}))
}
const menuDialog = ref(false)
const menuForm = reactive({ projectId:'',regionId:'',subsystemCode:'',menuKey:'',action:'VIEW' })
const availableMenus = computed(()=>overview.value?.menus.filter(m=>m.navigation_app_code===menuForm.subsystemCode) ?? [])
async function saveMenuGrant(){if(await perform(()=>scopeApi.menuGrant({scope:{projectId:menuForm.projectId,regionId:menuForm.regionId,subsystemCode:menuForm.subsystemCode},menuKey:menuForm.menuKey,action:menuForm.action,allowed:true,checked:false,expectedVersion:version(menuForm.projectId)})))menuDialog.value=false}
async function toggleMenu(row: MenuGrant){await perform(()=>scopeApi.menuGrant({scope:tuple(row),menuKey:row.stable_menu_key,action:row.op_code,allowed:isL1.value?!row.l1_allowed:row.l1_allowed,checked:!row.l2_checked,expectedVersion:version(row.project_id)}))}
const actionDialog=ref(false)
const actionForm=reactive({menuKey:'',action:'VIEW',enabled:true})
async function saveAction(){if(await perform(()=>scopeApi.menuAction({...actionForm})))actionDialog.value=false}
const operationDialog=ref(false)
const operationForm=reactive({code:'',name:'',category:'BUSINESS',effect:'WRITE',readonly:false,status:'active'})
function openOperation(row?:Operation){Object.assign(operationForm,{code:row?.op_code??'',name:row?.op_name??'',category:row?.op_category??'BUSINESS',effect:row?.effect_kind??'WRITE',readonly:row?.allow_readonly??false,status:row?.status??'active'});operationDialog.value=true}
async function saveOperation(){if(await perform(()=>scopeApi.operation({...operationForm})))operationDialog.value=false}
onMounted(load)

const appDialog=ref(false)
const appForm=reactive({code:'',name:'',permissionSystem:'',entryBasePath:'/',enabled:false,contextContract:1,expectedVersion:null as number|null})
function openApp(row?: NonNullable<Overview['applications']>[number]) {
  Object.assign(appForm,{code:row?.app_code??'',name:row?.display_name??'',permissionSystem:row?.iam_system_code??'',entryBasePath:row?.entry_base_path??'/',enabled:row?.enabled===1,contextContract:row?.context_contract_max??1,expectedVersion:row?.row_version??null})
  appDialog.value=true
}
async function saveApp(){if(await perform(()=>scopeApi.application({...appForm})))appDialog.value=false}
</script>

<template>
  <section class="scope-page" v-loading="loading">
    <header class="heading"><div><h1>分级人员与权限</h1><p>{{ names[level ?? ''] }} · {{ ctx?.readOnly ? '只读查看' : '在当前授权范围内管理' }}</p></div><el-button @click="load">刷新</el-button></header>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-card v-if="ctx" class="scope-banner">
      <el-descriptions :column="3">
        <el-descriptions-item label="项目">{{ ctx.projectId || '全局范围' }}</el-descriptions-item>
        <el-descriptions-item label="区域">{{ ctx.regionId || '按项目授权' }}</el-descriptions-item>
        <el-descriptions-item label="子系统">{{ ctx.subsystemCode || '按项目授权' }}</el-descriptions-item>
      </el-descriptions>
      <p v-if="isL3" class="hint">新增操作员自动继承上述项目、区域和子系统；这些属性已锁定。</p>
    </el-card>
    <el-tabs v-if="overview" model-value="people">
      <el-tab-pane label="人员管理" name="people">
        <div class="toolbar"><span>当前范围内 {{ people.length }} 名人员</span><el-button v-if="!readonly" type="primary" @click="openUser">{{ isL3?'创建业务操作员':isL1?'创建下级账号':'任命子系统管理员' }}</el-button></div>
        <el-table :data="people" row-key="id">
          <el-table-column prop="username" label="用户名" min-width="160" />
          <el-table-column prop="display_name" label="姓名" min-width="110" />
          <el-table-column label="层级" min-width="130"><template #default="{row}">{{ names[row.admin_level] || '旧账号待核定' }}</template></el-table-column>
          <el-table-column prop="project_id" label="项目" min-width="120" /><el-table-column prop="region_id" label="区域" min-width="120" /><el-table-column prop="subsystem_code" label="子系统" width="100" />
          <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='active'?'success':'info'">{{ row.status==='active'?'启用':'停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" min-width="290"><template #default="{row}"><template v-if="mayManage(row)">
            <el-button link type="primary" @click="changeStatus(row)">{{ row.status==='active'?'停用':'启用' }}</el-button>
            <el-button link @click="resetPassword(row)">重置密码</el-button>
            <el-button v-if="isL3 || canConfigure&&['L1_READONLY','L2_PROJECT'].includes(row.admin_level)" link @click="bindUser=row;bindRoles=[...row.roleIds]">分配岗位</el-button>
            <el-button link type="danger" @click="deleteUser(row)">删除</el-button>
          </template><span v-else class="hint">只读</span></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="业务岗位" name="roles">
        <div class="toolbar"><span>{{ isL3?'只能选择上级已许可的菜单动作':'岗位由各子系统管理员定义' }}</span><el-button v-if="(isL3||canConfigure)&&!readonly" type="primary" @click="openRole()">新增岗位</el-button></div>
        <el-table :data="roles" row-key="id"><el-table-column prop="role_name" label="岗位名称" /><el-table-column prop="business_role_code" label="业务代码" /><el-table-column prop="status" label="状态" />
          <el-table-column label="已配置动作" min-width="240"><template #default="{row}">{{ row.operations.map((a: {menuKey:string;action:string})=>a.menuKey+' / '+a.action).join('；') || '未配置' }}</template></el-table-column>
          <el-table-column v-if="(isL3||canConfigure)&&!readonly" label="操作"><template #default="{row}"><el-button link @click="openRole(row)">编辑</el-button><el-button link type="danger" @click="deleteRole(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane v-if="isL1" label="项目与区域" name="projects">
        <div class="toolbar"><h3>项目</h3><el-button v-if="canConfigure" @click="openProject()">新增项目</el-button></div>
        <el-table :data="overview.projects"><el-table-column prop="project_id" label="项目编码" /><el-table-column prop="project_name" label="项目名称" /><el-table-column prop="status" label="状态" /><el-table-column v-if="canConfigure" label="操作"><template #default="{row}"><el-button link @click="openProject(row)">编辑</el-button></template></el-table-column></el-table>
        <div class="toolbar"><h3>区域</h3><el-button v-if="canConfigure" @click="regionDialog=true">新增或更新区域</el-button></div>
        <el-table :data="overview.regions"><el-table-column prop="project_id" label="项目" /><el-table-column prop="region_id" label="区域编码" /><el-table-column prop="region_name" label="区域名称" /><el-table-column prop="status" label="状态" /></el-table>
      </el-tab-pane>
      <el-tab-pane v-if="!isL3" label="项目授权" name="grants">
        <div class="toolbar"><h3>区域与子系统</h3><el-button v-if="canConfigure" @click="grantDialog=true">许可区域与子系统</el-button></div>
        <el-table :data="overview.systemGrants"><el-table-column prop="project_id" label="项目" /><el-table-column prop="region_id" label="区域" /><el-table-column prop="subsystem_code" label="子系统" /><el-table-column label="L1许可"><template #default="{row}">{{ row.l1_allowed?'已许可':'未许可' }}</template></el-table-column><el-table-column label="L2启用"><template #default="{row}">{{ row.l2_enabled?'已启用':'未启用' }}</template></el-table-column>
          <el-table-column v-if="!readonly" label="操作"><template #default="{row}"><el-button link :disabled="!isL1&&!row.l1_allowed" @click="toggleSystem(row)">{{ isL1?(row.l1_allowed?'撤销许可':'许可'):(row.l2_enabled?'停用':'启用') }}</el-button></template></el-table-column></el-table>
        <div class="toolbar"><h3>菜单与动作</h3><el-button v-if="canConfigure" @click="menuDialog=true">许可菜单动作</el-button></div>
        <el-table :data="overview.menuGrants"><el-table-column prop="project_id" label="项目" /><el-table-column prop="region_id" label="区域" /><el-table-column prop="subsystem_code" label="系统" /><el-table-column prop="stable_menu_key" label="菜单" min-width="180" /><el-table-column prop="op_code" label="动作" /><el-table-column label="L1 / L2"><template #default="{row}">{{ row.l1_allowed?'许可':'未许可' }} / {{ row.l2_checked?'已勾选':'未勾选' }}</template></el-table-column><el-table-column v-if="!readonly" label="操作"><template #default="{row}"><el-button link :disabled="!isL1&&!row.l1_allowed" @click="toggleMenu(row)">{{ isL1?(row.l1_allowed?'撤销许可':'许可'):(row.l2_checked?'取消勾选':'勾选') }}</el-button></template></el-table-column></el-table>
      </el-tab-pane>

      <el-tab-pane v-if="isL1" label="子系统登记" name="applications">
        <div class="toolbar"><span>登记后仍须单独配置菜单、地址和项目许可</span><el-button v-if="canConfigure" type="primary" @click="openApp()">登记子系统</el-button></div>
        <el-table :data="overview.applications"><el-table-column prop="app_code" label="编码"/><el-table-column prop="display_name" label="名称"/><el-table-column prop="entry_base_path" label="入口路径"/><el-table-column prop="context_contract_max" label="上下文版本"/><el-table-column label="状态"><template #default="{row}">{{row.enabled===1?'启用':'停用'}}</template></el-table-column><el-table-column v-if="canConfigure" label="操作"><template #default="{row}"><el-button link :disabled="row.app_code==='DTS'" @click="openApp(row)">编辑</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane v-if="isL1" label="操作权限池" name="operations">
        <div class="toolbar"><span>只读动作仍需单独授权</span><div><el-button v-if="canConfigure" @click="actionDialog=true">登记菜单动作</el-button><el-button v-if="canConfigure" type="primary" @click="openOperation()">新增动作</el-button></div></div>
        <el-table :data="overview.operations"><el-table-column prop="op_code" label="动作码" /><el-table-column prop="op_name" label="动作名称" /><el-table-column prop="effect_kind" label="性质" /><el-table-column label="可授予只读用户"><template #default="{row}">{{ row.allow_readonly?'是':'否' }}</template></el-table-column><el-table-column prop="status" label="状态" /><el-table-column v-if="canConfigure" label="操作"><template #default="{row}"><el-button link @click="openOperation(row)">编辑</el-button></template></el-table-column></el-table>
      </el-tab-pane>
    </el-tabs>


    <el-dialog v-model="appDialog" title="子系统登记" width="600px"><el-form label-width="130px" @submit.prevent="saveApp">
      <el-form-item label="系统编码"><el-input v-model="appForm.code" :disabled="appForm.expectedVersion!==null" placeholder="大写字母、数字或下划线"/></el-form-item>
      <el-form-item label="系统名称"><el-input v-model="appForm.name"/></el-form-item>
      <el-form-item label="权限码前缀"><el-input v-model="appForm.permissionSystem" :disabled="appForm.expectedVersion!==null"/></el-form-item>
      <el-form-item label="入口路径"><el-input v-model="appForm.entryBasePath"/></el-form-item>
      <el-form-item label="上下文版本"><el-select v-model="appForm.contextContract"><el-option :value="1" label="现行契约"/><el-option :value="2" label="已完成区域上下文接入"/></el-select></el-form-item>
      <el-form-item label="启用"><el-switch v-model="appForm.enabled"/></el-form-item>
    </el-form><template #footer><el-button @click="appDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveApp">保存</el-button></template></el-dialog>
    <el-dialog v-model="userDialog" :title="isL3?'创建业务操作员':'创建下级账号'" width="660px" destroy-on-close>
      <el-form label-width="110px" @submit.prevent="saveUser">
        <el-form-item label="用户名" required><el-input v-model="userForm.username" maxlength="64" @input="invalidateName" @blur="checkUsername" /><p v-if="checkState==='occupied'" class="error-text">用户名已被占用，请修改后再提交。</p><p v-else-if="checkState==='error'" class="error-text">查重失败，请重新检查。</p><p v-else-if="checkState==='available'" class="success-text">用户名可用</p><p v-else-if="checkState==='checking'" class="hint">正在检查…</p></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="userForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="userForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="账号层级"><el-select v-if="isL1" v-model="userForm.adminLevel"><el-option label="项目管理员 L2" value="L2_PROJECT" /><el-option label="全局只读用户" value="L1_READONLY" /></el-select><span v-else>{{ names[userForm.adminLevel] }}</span></el-form-item>
        <template v-if="userForm.adminLevel!=='L1_READONLY'">
          <el-form-item label="项目" required><el-input v-if="!isL1" :model-value="userForm.projectId" readonly aria-label="继承项目" /><el-select v-else v-model="userForm.projectId"><el-option v-for="p in overview?.projects.filter(p=>p.status==='active')" :key="p.project_id" :label="p.project_name" :value="p.project_id" /></el-select></el-form-item>
          <template v-if="!isL1">
            <el-form-item label="区域" required><el-input v-if="isL3" :model-value="userForm.regionId" readonly aria-label="继承区域" /><el-select v-else v-model="userForm.regionId" @change="userForm.subsystemCode=''"><el-option v-for="r in userRegions" :key="r.region_id" :label="r.region_name" :value="r.region_id" /></el-select></el-form-item>
            <el-form-item label="子系统" required><el-input v-if="isL3" :model-value="userForm.subsystemCode" readonly aria-label="继承子系统" /><el-select v-else v-model="userForm.subsystemCode"><el-option v-for="s in userSystems" :key="s.subsystem_code" :label="s.subsystem_code" :value="s.subsystem_code" /></el-select></el-form-item>
          </template>
        </template>
        <el-form-item v-if="isL1" label="所属组织" required><el-cascader v-model="userForm.orgCodes" :options="organizations" :props="{value:'code',label:'name',checkStrictly:true}" clearable /></el-form-item>
        <el-form-item v-if="isL3||['L1_READONLY','L2_PROJECT'].includes(userForm.adminLevel)" label="业务岗位" required><el-select v-model="userForm.roleIds" multiple><el-option v-for="r in roles.filter(r=>r.status==='active'&&(isL3?r.role_scope==='REGION_SYSTEM':userForm.adminLevel==='L2_PROJECT'?r.role_scope==='PROJECT'&&r.project_id===userForm.projectId:r.role_scope==='GLOBAL'))" :key="r.id" :label="r.role_name" :value="r.id" /></el-select></el-form-item>
        <el-form-item v-if="userForm.adminLevel!=='L1_READONLY'" label="只读模式"><el-switch v-model="userForm.readOnly" /></el-form-item>
      </el-form><template #footer><el-button @click="userDialog=false;userForm.password=''">取消</el-button><el-button type="primary" :disabled="!canSubmitUser" :loading="saving" @click="saveUser">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="roleDialog" title="业务岗位" width="680px">
      <el-form label-width="100px"><el-form-item v-if="canConfigure" label="岗位范围"><el-select v-model="roleForm.projectId" :disabled="!!roleId"><el-option value="" label="全局只读岗位" /><el-option v-for="p in overview?.projects" :key="p.project_id" :value="p.project_id" :label="p.project_name+'读取岗位'" /></el-select></el-form-item><el-form-item label="岗位名称"><el-input v-model="roleForm.name" maxlength="64" /></el-form-item><el-form-item label="业务代码"><el-input v-model="roleForm.code" maxlength="64" /></el-form-item><el-form-item label="状态"><el-select v-model="roleForm.status"><el-option label="启用" value="active" /><el-option label="停用" value="inactive" /></el-select></el-form-item><el-form-item label="菜单动作"><el-select v-model="roleForm.actions" multiple filterable style="width:100%"><el-option v-for="a in roleOptions" :key="JSON.stringify({menuKey:a.menuKey,action:a.action})" :label="a.name+' / '+a.action" :value="JSON.stringify({menuKey:a.menuKey,action:a.action})" /></el-select></el-form-item></el-form>
      <el-alert title="上级撤销的动作不可重新授予；编辑时请移除失效动作。" type="info" :closable="false" />
      <template #footer><el-button @click="roleDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRole">保存</el-button></template>
    </el-dialog>
    <el-dialog :model-value="!!bindUser" title="分配业务岗位" width="520px" @close="bindUser=null"><el-select v-model="bindRoles" multiple style="width:100%"><el-option v-for="r in roles.filter(r=>r.status==='active'&&(isL3?r.role_scope==='REGION_SYSTEM':bindUser?.admin_level==='L2_PROJECT'?r.role_scope==='PROJECT'&&r.project_id===bindUser.project_id:r.role_scope==='GLOBAL'))" :key="r.id" :label="r.role_name" :value="r.id" /></el-select><template #footer><el-button @click="bindUser=null">取消</el-button><el-button type="primary" :disabled="!bindRoles.length" :loading="saving" @click="saveBindings">保存</el-button></template></el-dialog>
    <el-dialog v-model="projectDialog" title="项目配置" width="540px"><el-form label-width="100px"><el-form-item label="项目编码"><el-input v-model="projectForm.projectId" :disabled="projectForm.expectedVersion!==undefined" /></el-form-item><el-form-item label="项目名称"><el-input v-model="projectForm.name" /></el-form-item><el-form-item label="单位名称"><el-input v-model="projectForm.company" /></el-form-item><el-form-item label="状态"><el-select v-model="projectForm.status"><el-option label="启用" value="active" /><el-option label="停用" value="disabled" /></el-select></el-form-item></el-form><template #footer><el-button @click="projectDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProject">保存</el-button></template></el-dialog>
    <el-dialog v-model="regionDialog" title="区域配置" width="540px"><el-form label-width="100px"><el-form-item label="项目"><el-select v-model="regionForm.projectId"><el-option v-for="p in overview?.projects" :key="p.project_id" :label="p.project_name" :value="p.project_id" /></el-select></el-form-item><el-form-item label="区域编码"><el-input v-model="regionForm.regionId" /></el-form-item><el-form-item label="区域名称"><el-input v-model="regionForm.name" /></el-form-item><el-form-item label="区域类型"><el-input v-model="regionForm.type" /></el-form-item><el-form-item label="状态"><el-select v-model="regionForm.status"><el-option label="启用" value="active" /><el-option label="停用" value="disabled" /></el-select></el-form-item></el-form><template #footer><el-button @click="regionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRegion">保存</el-button></template></el-dialog>
    <el-dialog v-model="grantDialog" title="许可区域与子系统" width="540px"><el-form label-width="100px"><el-form-item label="项目"><el-select v-model="grantForm.projectId"><el-option v-for="p in overview?.projects" :key="p.project_id" :label="p.project_name" :value="p.project_id" /></el-select></el-form-item><el-form-item label="区域"><el-select v-model="grantForm.regionId"><el-option v-for="r in grantRegions" :key="r.region_id" :label="r.region_name" :value="r.region_id" /></el-select></el-form-item><el-form-item label="子系统"><el-select v-model="grantForm.subsystemCode"><el-option v-for="a in overview?.applications" :key="a.app_code" :label="a.display_name" :value="a.app_code" /></el-select></el-form-item></el-form><template #footer><el-button @click="grantDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveGrant">许可</el-button></template></el-dialog>
    <el-dialog v-model="menuDialog" title="许可菜单动作" width="560px"><el-form label-width="100px"><el-form-item label="授权范围"><el-select :model-value="JSON.stringify({projectId:menuForm.projectId,regionId:menuForm.regionId,subsystemCode:menuForm.subsystemCode})" @change="(v:string)=>Object.assign(menuForm,JSON.parse(v))"><el-option v-for="g in overview?.systemGrants.filter(g=>g.l1_allowed)" :key="JSON.stringify(tuple(g))" :value="JSON.stringify(tuple(g))" :label="g.project_id+' / '+g.region_id+' / '+g.subsystem_code" /></el-select></el-form-item><el-form-item label="菜单"><el-select v-model="menuForm.menuKey"><el-option v-for="m in availableMenus" :key="m.stable_menu_key" :label="m.menu_name" :value="m.stable_menu_key" /></el-select></el-form-item><el-form-item label="动作"><el-select v-model="menuForm.action"><el-option v-for="o in overview?.operations" :key="o.op_code" :label="o.op_name" :value="o.op_code" /></el-select></el-form-item></el-form><template #footer><el-button @click="menuDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMenuGrant">许可</el-button></template></el-dialog>
    <el-dialog v-model="actionDialog" title="登记菜单可选动作" width="560px"><el-form label-width="100px"><el-form-item label="菜单"><el-select v-model="actionForm.menuKey" filterable><el-option v-for="m in overview?.menus" :key="m.stable_menu_key" :label="m.navigation_app_code+' / '+m.menu_name" :value="m.stable_menu_key" /></el-select></el-form-item><el-form-item label="动作"><el-select v-model="actionForm.action"><el-option v-for="o in overview?.operations" :key="o.op_code" :label="o.op_name" :value="o.op_code" /></el-select></el-form-item><el-form-item label="启用"><el-switch v-model="actionForm.enabled" /></el-form-item></el-form><template #footer><el-button @click="actionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveAction">保存</el-button></template></el-dialog>
    <el-dialog v-model="operationDialog" title="操作权限字典" width="560px"><el-form label-width="110px"><el-form-item label="动作编码"><el-input v-model="operationForm.code" /></el-form-item><el-form-item label="动作名称"><el-input v-model="operationForm.name" /></el-form-item><el-form-item label="分类"><el-input v-model="operationForm.category" /></el-form-item><el-form-item label="动作性质"><el-select v-model="operationForm.effect"><el-option label="读取" value="READ" /><el-option label="写入" value="WRITE" /><el-option label="下载或导出" value="EXPORT" /></el-select></el-form-item><el-form-item label="只读可授权"><el-switch v-model="operationForm.readonly" /></el-form-item><el-form-item label="状态"><el-select v-model="operationForm.status"><el-option label="启用" value="active" /><el-option label="停用" value="disabled" /></el-select></el-form-item></el-form><template #footer><el-button @click="operationDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveOperation">保存</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.scope-page{padding:24px;max-width:1600px;margin:auto}.heading,.toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px}.heading{margin-bottom:20px}.heading h1{font-size:24px;margin:0 0 8px}.heading p,.hint{color:var(--el-text-color-secondary)}.scope-banner{margin:16px 0}.toolbar{margin:16px 0}.error-text{color:var(--el-color-danger);margin:4px 0}.success-text{color:var(--el-color-success);margin:4px 0}.el-form .el-select,.el-cascader{width:100%}.el-form p{width:100%;line-height:1.6}.el-table{margin-bottom:20px}
</style>
