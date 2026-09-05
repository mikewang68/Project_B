import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 应用级状态（主题/模块）
 * 对齐统一基线：Pinia 作为全局状态，会话、权限、界面、跨组件状态
 */
export const useAppStore = defineStore('app', () => {
  /** 当前主题：dark=大屏/三维（默认），light=管理端 */
  const theme = ref<'dark' | 'light'>('dark')
  /** 当前模块标识（后续扩展：A~H） */
  const activeModule = ref('A')

  function setTheme(t: 'dark' | 'light') {
    theme.value = t
    document.documentElement.setAttribute('data-theme', t)
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  function setModule(m: string) {
    activeModule.value = m
  }

  return { theme, activeModule, setTheme, toggleTheme, setModule }
})
