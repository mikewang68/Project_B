import type { EdgeNodeStatus, FenceRecord, FenceStatus } from '@/types/fence'

/** 后端围栏 DTO（DemoFence，字段与前端 FenceRecord 基本一致） */
export interface FenceDto {
  id: string
  name: string
  kind: FenceRecord['kind']
  tone: FenceRecord['tone']
  area: string
  riskLevel?: string
  version: string
  status: FenceStatus
  effectiveAt: string
  expiresAt: string
  teams: string
  approver: string
  edgeSynced: number
  edgeTotal: number
  polygon: Array<{ x: number; y: number }>
  nodes: Array<{ id: string; state: EdgeNodeStatus['state'] }>
}

export function mapFence(dto: unknown): FenceRecord {
  const d = (dto ?? {}) as Partial<FenceDto>
  return {
    id: d.id ?? '',
    name: d.name ?? '',
    kind: d.kind ?? '临时围栏',
    tone: d.tone ?? 'normal',
    area: d.area ?? '自定义区域',
    version: d.version ?? 'v1.0',
    status: (d.status ?? '草稿') as FenceStatus,
    effectiveAt: d.effectiveAt ?? '',
    expiresAt: d.expiresAt ?? '',
    teams: d.teams ?? '',
    approver: d.approver ?? '—',
    riskLevel: d.riskLevel,
    edgeSynced: d.edgeSynced ?? 0,
    edgeTotal: d.edgeTotal ?? 4,
    polygon: Array.isArray(d.polygon) ? d.polygon.map((p) => ({ x: p.x, y: p.y })) : [],
    nodes: Array.isArray(d.nodes)
      ? d.nodes.map((n) => ({ id: n.id, state: n.state }))
      : [],
  }
}

export function mapFenceList(dto: unknown): FenceRecord[] {
  const d = (dto ?? {}) as { list?: unknown[] }
  return Array.isArray(d.list) ? d.list.map(mapFence) : []
}
