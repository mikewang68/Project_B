import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 全局注册 Element Plus 所有图标，使 <Plus />、<component :is="'DataAnalysis'" /> 等可直接使用
for (const [iconName, iconComponent] of Object.entries(ElementPlusIconsVue)) {
  app.component(iconName, iconComponent)
}

// 说明：用户/角色/权限（IAM）已剥离为独立系统 iam-system，本工程不再内置认证与权限指令；
// 后续在此对接独立 IAM 下发的登录态/令牌即可。当前为本地免登录运行模式。

// 默认深色主题
document.documentElement.setAttribute('data-theme', 'dark')

app.mount('#app')
