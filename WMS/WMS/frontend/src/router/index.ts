import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import InventoryView from '@/views/InventoryView.vue'
import ForbiddenView from '@/views/ForbiddenView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import LoginView from '@/views/LoginView.vue'
import UserManagementView from '@/views/UserManagementView.vue'
import MasterDataView from '@/views/MasterDataView.vue'
import StockinView from '@/views/StockinView.vue'
import StockoutView from '@/views/StockoutView.vue'
import FinanceView from '@/views/FinanceView.vue'
import IntegrationView from '@/views/IntegrationView.vue'
import { useAuthStore } from '@/stores/auth'

export interface WmsRouteMeta {
  title: string
  permission?: string
  public?: boolean
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: LoginView, meta: { title: '登录', public: true } },
  { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '仓储工作台', permission: 'dashboard:view' } },
  { path: '/inventory', name: 'inventory', component: InventoryView, meta: { title: '库存查询', permission: 'inventory:read' } },
  { path: '/system/users', name: 'users', component: UserManagementView, meta: { title: '用户管理', permission: 'system:user:read' } },
  { path: '/master-data', name: 'master-data', component: MasterDataView, meta: { title: '仓库基础资料', permission: 'master:read' } },
  { path: '/stockin', name: 'stockin', component: StockinView, meta: { title: '入库管理', permission: 'stockin:read' } },
  { path: '/stockout', name: 'stockout', component: StockoutView, meta: { title: '出库管理', permission: 'stockout:read' } },
  { path: '/finance', name: 'finance', component: FinanceView, meta: { title: '财务统计', permission: 'finance:read' } },
  { path: '/integration', name: 'integration', component: IntegrationView, meta: { title: '导入导出与集成', permission: 'integration:manage' } },
  { path: '/403', name: 'forbidden', component: ForbiddenView, meta: { title: '无权访问' } },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView, meta: { title: '页面不存在' } },
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.restoreSession()
  if (to.meta.public) return auth.authenticated && to.name === 'login' ? { name: 'dashboard' } : true
  if (!auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (!auth.hasPermission(to.meta.permission)) return { name: 'forbidden' }
  return true
})

export default router

declare module 'vue-router' {
  interface RouteMeta extends WmsRouteMeta {}
}
