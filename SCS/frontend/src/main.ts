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
import { watch } from 'vue'
import { useAuthStore } from '@/stores/auth'

// The callback establishes an HttpOnly session; routing restores current server authorization.
const app = createApp(App)
app.use(createPinia()).use(router)
for (const component of [ElButton, ElDialog, ElDrawer, ElIcon, ElInput, ElInputNumber, ElOption, ElPagination, ElSegmented, ElSelect, ElSlider, ElSwitch, ElTable, ElTableColumn, ElTag]) app.component(component.name!, component)
app.directive('loading', ElLoading.directive)
const permissionWatchers=new WeakMap<HTMLElement,()=>void>()
function applyPermission(el:HTMLElement,permission:string){
  const auth=useAuthStore()
  const allowed=auth.can(permission)&&(!auth.user?.readOnly||permission.endsWith(':export'))
  if(!allowed){el.setAttribute('disabled','');el.setAttribute('aria-disabled','true');el.dataset.permissionBlocked='1';el.title='当前账号未获此操作权限'}
  else if(el.dataset.permissionBlocked){el.removeAttribute('disabled');el.removeAttribute('aria-disabled');delete el.dataset.permissionBlocked;el.removeAttribute('title')}
}
app.directive('permission',{
  mounted(el:HTMLElement,binding){
    const auth=useAuthStore()
    permissionWatchers.set(el,watch(()=>[auth.user,auth.can(String(binding.value))],()=>applyPermission(el,String(binding.value)),{immediate:true}))
  },
  updated(el:HTMLElement,binding){applyPermission(el,String(binding.value))},
  unmounted(el:HTMLElement){permissionWatchers.get(el)?.();permissionWatchers.delete(el)},
})
app.mount('#app')
