import { describe, expect, it } from 'vitest'
import { mapFence, mapFenceList } from './fence'

describe('fence adapter', () => {
  it('maps a backend fence with edge nodes', () => {
    const f = mapFence({
      id: 'FENCE-001', name: '龙门吊动态禁区', kind: '危险区域', tone: 'danger', area: '龙门吊作业区',
      version: 'v3.2', status: '已生效', teams: '装卸一班', approver: '王海', edgeSynced: 4, edgeTotal: 4,
      polygon: [{ x: 1, y: 1 }, { x: 2, y: 1 }, { x: 2, y: 2 }],
      nodes: [{ id: 'EDGE-01', state: 'success' }],
    })
    expect(f.status).toBe('已生效')
    expect(f.polygon).toHaveLength(3)
    expect(f.nodes[0]!.id).toBe('EDGE-01')
  })

  it('defaults safely and maps a list', () => {
    expect(mapFence(undefined).status).toBe('草稿')
    expect(mapFence(undefined).polygon).toEqual([])
    expect(mapFenceList({ list: [{ id: 'a' }, { id: 'b' }] })).toHaveLength(2)
    expect(mapFenceList(undefined)).toEqual([])
  })
})
