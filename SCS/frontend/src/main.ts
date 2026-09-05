import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElIcon, ElInput, ElLoading, ElOption, ElSegmented, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTag } from 'element-plus'
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

const app = createApp(App)
app.use(createPinia()).use(router)
for (const component of [ElButton, ElIcon, ElInput, ElOption, ElSegmented, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTag]) app.component(component.name!, component)
app.directive('loading', ElLoading.directive)
app.mount('#app')
