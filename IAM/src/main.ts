import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/index.scss'
import { vPerm } from './permissions/directive'
// 引入偏好 store：模块加载时即按 localStorage 应用初始皮肤，避免首屏闪烁
import { usePreferenceStore } from './stores/preference'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 激活外观偏好（皮肤/布局），初始皮肤已在 preference 模块加载时应用
usePreferenceStore()

// 全局注册 Element Plus 所有图标，使 <Plus />、<component :is="'User'" /> 等可直接使用
for (const [iconName, iconComponent] of Object.entries(ElementPlusIconsVue)) {
  app.component(iconName, iconComponent)
}

// 按钮级权限指令：v-perm="'code'" / v-perm:any / v-perm:all
app.directive('perm', vPerm)

app.mount('#app')
