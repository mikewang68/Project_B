import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const OverviewView = () => import('@/views/OverviewView.vue')
const PersonnelLocationView = () => import('@/views/PersonnelLocationView.vue')
const FenceManagementView = () => import('@/views/FenceManagementView.vue')
const CollisionOverviewView = () => import('@/views/CollisionOverviewView.vue')
const AiReviewView = () => import('@/views/AiReviewView.vue')
const AlertCenterView = () => import('@/views/AlertCenterView.vue')
const AnalyticsView = () => import('@/views/AnalyticsView.vue')
const RuleConfigView = () => import('@/views/RuleConfigView.vue')
const OperationsView = () => import('@/views/OperationsView.vue')
const SafetyScreenView = () => import('@/views/SafetyScreenView.vue')
const MobileLayout = () => import('@/components/mobile/MobileLayout.vue')
const MobileHomeView = () => import('@/views/mobile/MobileHomeView.vue')
const MobileAlertsView = () => import('@/views/mobile/MobileAlertsView.vue')
const MobileAlertDetailView = () => import('@/views/mobile/MobileAlertDetailView.vue')

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/overview' },
  {
    path: '/overview',
    name: 'overview',
    component: OverviewView,
    meta: { title: '安全态势', subtitle: '掌握现场风险、告警与作业运行状态' },
  },
  {
    path: '/people', name: 'people', component: PersonnelLocationView,
    meta: { title: '人员定位', subtitle: '实时掌握作业人员位置、手环状态与区域安全情况' },
  },
  {
    path: '/fences', name: 'fences', component: FenceManagementView,
    meta: { title: '电子围栏', subtitle: '配置人员作业区域、危险区域及临时安全边界' },
  },
  {
    path: '/devices', name: 'devices', component: CollisionOverviewView,
    meta: { title: '设备防碰撞', subtitle: '实时监测设备间距、运行趋势与碰撞风险' },
  },
  {
    path: '/ai', name: 'ai-review', component: AiReviewView,
    meta: { title: 'AI违规识别', subtitle: '集中复核现场 AI 识别事件与安全证据' },
  },
  {
    path: '/alarms', name: 'alert-center', component: AlertCenterView,
    meta: { title: '告警中心', subtitle: '统一管理人员、设备、AI与系统安全事件' },
  },
  {
    path: '/analytics', name: 'analytics', component: AnalyticsView,
    meta: { title: '统计分析', subtitle: '分析安全风险趋势、重点区域与告警处置效率' },
  },
  {
    path: '/rules', name: 'rules', component: RuleConfigView,
    meta: { title: '规则配置', subtitle: '统一管理安全判定、告警升级与设备联动策略' },
  },
  {
    path: '/operations', name: 'operations', component: OperationsView,
    meta: { title: '运维监控', subtitle: '实时监控平台服务、边缘节点、感知设备与关键接口运行状态' },
  },
  {
    // 安全大屏：独立全屏页面，不套管理端外壳
    path: '/safety-screen', name: 'safety-screen', component: SafetyScreenView,
    meta: { standalone: true, title: '安全态势中心' },
  },
  {
    // 移动端告警处置：独立手机端页面
    path: '/mobile',
    component: MobileLayout,
    meta: { standalone: true },
    redirect: '/mobile/home',
    children: [
      { path: 'home', name: 'mobile-home', component: MobileHomeView },
      { path: 'alerts', name: 'mobile-alerts', component: MobileAlertsView },
      { path: 'alert/:id', name: 'mobile-alert-detail', component: MobileAlertDetailView },
    ],
  },
  { path: '/demo', redirect: '/overview' },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

export default createRouter({ history: createWebHistory(), routes })
