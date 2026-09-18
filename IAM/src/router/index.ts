import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true },
    redirect: '/system/users',
    children: [
      {
        path: 'system/users',
        name: 'system-users',
        component: () => import('@/views/system/UserManageView.vue'),
        meta: { title: '用户管理', perm: 'iam:user:list:view' },
      },
      {
        path: 'system/roles',
        name: 'system-roles',
        component: () => import('@/views/system/RoleManageView.vue'),
        meta: { title: '角色管理', perm: 'iam:role:list:view' },
      },
      {
        path: 'system/menus',
        name: 'system-menus',
        component: () => import('@/views/system/MenuPermView.vue'),
        meta: { title: '菜单与权限', perm: 'iam:menu:tree:view' },
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无权限访问', requiresAuth: true },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/** 白名单：不需要登录即可访问的路径 */
const WHITE_LIST = ['/login']

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  // 页面刷新后若本地仍有 token，先通过 /auth/me 恢复会话
  if (!auth.hydrated) {
    await auth.restoreFromSession()
  }

  // 已登录用户访问登录页 → 跳到其落地页
  if (to.path === '/login' && auth.isLoggedIn) {
    next(auth.landingPath)
    return
  }

  // 白名单直接放行
  if (WHITE_LIST.includes(to.path)) {
    next()
    return
  }

  // 未登录 → 跳登录页，带 redirect
  if (!auth.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录：校验路由级权限（meta.perm）；超级管理员在 permCodes 中已动态拥有全部权限
  if (to.meta.perm && !auth.permCodes.has(to.meta.perm as string)) {
    next('/403')
    return
  }

  next()
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} | IAM统一权限` : 'IAM统一身份与权限管理'
})

// 任意请求返回 401：清理后回到登录页（带当前路径用于登录后跳回）
window.addEventListener('app:unauthorized', () => {
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

export default router
