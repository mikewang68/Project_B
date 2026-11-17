import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import FenceList from './FenceList.vue'
import type { FenceRecord } from '@/types/fence'

function fence(id: string, status: FenceRecord['status']): FenceRecord {
  return {
    id, name: id, kind: '危险区域', tone: 'danger', area: '装卸区 A', version: 'v1.0',
    status, effectiveAt: '', expiresAt: '', teams: '', approver: '', edgeSynced: 0, edgeTotal: 4,
    polygon: [], nodes: [],
  }
}

describe('FenceList disabled visibility', () => {
  it('shows the empty state and the hidden count when only disabled fences remain', async () => {
    const wrapper = mount(FenceList, {
      props: { fences: [], selectedId: '', disabledCount: 2, showDisabled: false },
      global: { stubs: { StatusBadge: true } },
    })
    expect(wrapper.text()).toContain('暂无生效或待发布围栏')
    expect(wrapper.text()).toContain('已停用的 2 条围栏默认隐藏')
    const toggle = wrapper.find('.fence-list-footer button')
    expect(toggle.text()).toBe('显示已停用围栏（2）')
    await toggle.trigger('click')
    expect(wrapper.emitted('toggle-disabled')).toHaveLength(1)
    wrapper.unmount()
  })

  it('lists disabled fences when the audit toggle is on', () => {
    const wrapper = mount(FenceList, {
      props: { fences: [fence('FENCE-001', '已停用')], selectedId: 'FENCE-001', disabledCount: 1, showDisabled: true },
      global: { stubs: { StatusBadge: true } },
    })
    expect(wrapper.findAll('.fence-list-item')).toHaveLength(1)
    expect(wrapper.find('.fence-list-footer button').text()).toBe('隐藏已停用围栏')
    wrapper.unmount()
  })
})
