import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AIDetectionImage from './AIDetectionImage.vue'

const props = { scene: 'helmet' as const, boxes: [], camera: 'CAM-TEST', time: '12:00' }
describe('missing snapshot evidence', () => {
  it('shows an explicit unavailable state without inventing a camera scene', () => {
    const wrapper = mount(AIDetectionImage, { props })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('现场截图不可用')
  })
  it('removes the live evidence badge after image retrieval fails', async () => {
    const wrapper = mount(AIDetectionImage, { props: { ...props, snapshotUrl: '/missing.jpg' } })
    await wrapper.get('image').trigger('error')
    expect(wrapper.find('.ai-image-live-badge').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('缺失图片不作为视觉证据')
  })
})
