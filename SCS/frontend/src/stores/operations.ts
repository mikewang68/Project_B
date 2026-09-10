import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { CloudLink } from '@/types/operations'
import { operationsApi } from '@/api/operations'
import { toCloudLink } from '@/adapters/operations'

/**
 * 云边链路全局状态：供运维页与顶部 Header 共享断网自治演示状态。
 * linkState 的权威来源是 GET /api/v1/edge/link（Backend），通过 syncFromBackend 同步，
 * 不再由前端本地切换。
 */
export const useOperationsStore = defineStore('operations', () => {
  const linkState = ref<CloudLink>('online')

  function setLink(state: CloudLink): void {
    linkState.value = state
  }

  /** 从 Backend 拉取云边链路聚合状态并同步 */
  async function syncFromBackend(): Promise<void> {
    const link = await operationsApi.link()
    linkState.value = toCloudLink(link.state)
  }

  const offlineAutonomy = () => linkState.value === 'disconnected' || linkState.value === 'link-error'

  return { linkState, setLink, syncFromBackend, offlineAutonomy }
})
