import { describe, expect, it } from 'vitest'
import {
  mapAiEvent,
  mapAiEventPage,
  mapAiMetrics,
  mapCameraInfo,
  mapCameraHealth,
  mapReviewStatus,
} from './aiEvent'

describe('ai event adapter', () => {
  it('maps a backend ai event and keeps linkedAlertId only when present', () => {
    const event = mapAiEvent({
      id: 'AI-1', type: '未佩戴安全帽', camera: 'CAM-07', cameraName: '装卸区 B 球机', area: '装卸区 B',
      confidence: 96.2, durationSec: 1.4, model: 'PPE-Detection-v2.4.1', threshold: 85, time: '22:14:08',
      status: '待复核', risk: '高', health: '正常', scene: 'helmet',
      boxes: [{ id: 'person', label: 'PERSON', score: 97, x: 40, y: 46, w: 23, h: 44, tone: 'person' }],
      rule: 'r', relatedPerson: 'p', relatedDevice: 'd', judgeText: 'j', timeline: [],
    })
    expect(event.status).toBe('待复核')
    expect(event.scene).toBe('helmet')
    expect(event.boxes).toHaveLength(1)
    expect(event.linkedAlertId).toBeUndefined()

    const confirmed = mapAiEvent({ id: 'AI-1', type: '未佩戴安全帽', status: '已确认违规', linkedAlertId: 'ALM-20260905-001' })
    expect(confirmed.linkedAlertId).toBe('ALM-20260905-001')
  })

  it('falls back unknown enums safely', () => {
    expect(mapReviewStatus('未知')).toBe('待复核')
    expect(mapCameraHealth('未知')).toBe('正常')
    const event = mapAiEvent({ id: 'X', type: '未知类型', boxes: null, timeline: null })
    expect(event.type).toBe('摄像头异常')
    expect(event.boxes).toEqual([])
    expect(event.timeline).toEqual([])
  })

  it('tolerates boxes without score (fence line)', () => {
    const event = mapAiEvent({
      id: 'AI-2', type: '翻越护栏',
      boxes: [{ id: 'rail', label: 'FENCE LINE', x: 12, y: 60, w: 76, h: 7, tone: 'zone' }],
    })
    expect(event.boxes[0]!.score).toBeUndefined()
  })

  it('maps page result with metrics and facets', () => {
    const page = mapAiEventPage({
      page: 1, pageSize: 20, total: 1,
      metrics: { today: 27, pending: 6, confirmed: 12, falsePositive: 9, cameraFault: 2 },
      facets: { areas: ['装卸区 A'], cameras: ['CAM-03'] },
      list: [{ id: 'AI-1', type: '人员滞留' }],
    })
    expect(page.metrics.today).toBe(27)
    expect(page.facets.areas).toEqual(['装卸区 A'])
    expect(page.list).toHaveLength(1)
    expect(mapAiMetrics({ today: 1 }).pending).toBe(0)
  })

  it('maps camera info', () => {
    const camera = mapCameraInfo({ cameraId: 'CAM-01', name: '球机', area: 'A', online: true, quality: 98.1, state: '正常', lastUpdated: 't' })
    expect(camera.online).toBe(true)
    expect(camera.state).toBe('正常')
  })
})
