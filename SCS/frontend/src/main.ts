import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElDialog, ElDrawer, ElIcon, ElInput, ElInputNumber, ElLoading, ElOption, ElPagination, ElSegmented, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTag } from 'element-plus/es'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/pagination/style/css'
import 'element-plus/es/components/segmented/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/slider/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
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
