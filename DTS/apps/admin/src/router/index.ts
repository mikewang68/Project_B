import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

// 数字孪生系统路由（IAM 已剥离为独立系统，本工程不再内置登录/权限守卫，本地免登录进入；
// 后续由独立 IAM 统一签发登录态后，可在此接入全局鉴权）。
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '数字孪生可视化系统' },
      },
      {
        path: 'bigscreen',
        name: 'bigscreen',
        component: () => import('@/views/BigScreenView.vue'),
        meta: { title: '指挥中心大屏' },
      },
      {
        path: 'calibration',
        name: 'calibration',
        component: () => import('@/views/CalibrationView.vue'),
        meta: { title: '核心区落位校核' },
      },
    ],
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

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} | B项目DT` : 'B项目DT'
})

export default router
