import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElDialog, ElDrawer, ElIcon, ElInput, ElInputNumber, ElLoading, ElOption, ElPagination, ElSegmented, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTag } from 'element-plus'
import 'element-plus/dist/index.css'
import '@/styles/tokens.scss'
import '@/styles/main.scss'
import '@/styles/overview.scss'
import '@/styles/personnel-fence.scss'
import '@/styles/collision.scss'
import '@/styles/ai.scss'
import '@/styles/alarms.scss'
import '@/styles/analytics.scss'
import '@/styles/rules.scss'
import '@/styles/operations.scss'
import '@/styles/screen.scss'
import '@/styles/mobile.scss'
import App from './App.vue'
import router from './router'
import { bootstrapSession } from '@/api/auth/session'

// 身份链路：若地址带 sso_ticket 则交换成本地会话；否则沿用已有 Cookie。
// 失败不阻塞挂载——未登录态由页面自身的 401 处理呈现。
void bootstrapSession()

const app = createApp(App)
app.use(createPinia()).use(router)
for (const component of [ElButton, ElDialog, ElDrawer, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination, ElSegmented, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTag]) app.component(component.name!, component)
app.directive('loading', ElLoading.directive)
app.mount('#app')
