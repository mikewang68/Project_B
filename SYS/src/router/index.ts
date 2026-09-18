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
    redirect: '/maintain/dict',
    children: [
      {
        path: 'maintain/dict',
        name: 'maintain-dict',
        component: () => import('@/views/maintain/DictView.vue'),
        meta: { title: '数据字典', perm: 'sys:dict:type:view' },
      },
      {
        path: 'maintain/logs',
        name: 'maintain-logs',
        component: () => import('@/views/maintain/LogView.vue'),
        meta: { title: '操作日志', perm: 'sys:log:list:view' },
      },
      {
        path: 'maintain/config',
        name: 'maintain-config',
        component: () => import('@/views/maintain/ConfigView.vue'),
        meta: { title: '系统配置', perm: 'sys:config:list:view' },
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

const WHITE_LIST = ['/login']

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  // 页面刷新后若本地仍有 token，先通过 SYS /auth/me 恢复会话
  if (!auth.hydrated) {
    await auth.restoreFromSession()
  }

  if (to.path === '/login' && auth.isLoggedIn) {
    next(auth.landingPath)
    return
  }
  if (WHITE_LIST.includes(to.path)) {
    next()
    return
  }
  if (!auth.isLoggedIn) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  if (to.meta.perm && !auth.permCodes.has(to.meta.perm as string)) {
    next('/403')
    return
  }
  next()
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} | SYS系统设置` : '系统设置与维护系统'
})

// 任意请求返回 401：清理后回到登录页（带当前路径用于登录后跳回）
window.addEventListener('app:unauthorized', () => {
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

export default router
