import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { CloudLink } from '@/types/operations'

/** 云边链路全局状态：供运维页与顶部 Header 共享断网自治演示状态 */
export const useOperationsStore = defineStore('operations', () => {
  const linkState = ref<CloudLink>('online')

  function setLink(state: CloudLink): void {
    linkState.value = state
  }

  const offlineAutonomy = () => linkState.value === 'disconnected' || linkState.value === 'link-error'

  return { linkState, setLink, offlineAutonomy }
})
