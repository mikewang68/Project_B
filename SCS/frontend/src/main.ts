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
import { permissionDirective } from '@/directives/permission'

// The callback establishes an HttpOnly session; routing restores current server authorization.
const app = createApp(App)
app.use(createPinia()).use(router)
for (const component of [ElButton, ElDialog, ElDrawer, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination, ElSegmented, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTag]) app.component(component.name!, component)
app.directive('loading', ElLoading.directive)
app.directive('permission', permissionDirective)
app.mount('#app')
