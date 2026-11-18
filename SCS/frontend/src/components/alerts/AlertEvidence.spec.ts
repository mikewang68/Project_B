import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AlertEvidence from './AlertEvidence.vue'

describe('alert AI evidence snapshot', () => {
  it('forwards the AI snapshot URL to the detection image', () => {
    const wrapper = mount(AlertEvidence, {
      props: {
        evidence: {
          kind: 'ai',
          scene: 'helmet',
          boxes: [],
          confidence: 95.4,
          model: 'YOLOv8-PPE-Demo',
          camera: 'CAM-07',
          time: '12:09:11',
          snapshotUrl: '/api/v1/ai-events/snapshots/snap_test.jpg',
        },
      },
    })
    expect(wrapper.get('image').attributes('href')).toBe('/api/v1/ai-events/snapshots/snap_test.jpg')
  })
})
