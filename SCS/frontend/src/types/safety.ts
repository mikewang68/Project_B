export type AlarmLevel = '提示' | '一般' | '严重' | '紧急'
export type EntityState = 'normal' | 'warning' | 'danger' | 'offline' | 'maintenance'

export interface WorkPlan {
  id: string
  name: string
  shift: string
  team: string
  area: string
  progress: number
  status: string
}

export interface Person {
  id: string
  name: string
  team: string
  role: string
  bracelet: string
  x: number
  y: number
  battery: number
  signal: number
  quality: string
  status: EntityState
  lastSeen: string
}

export interface Fence {
  id: string
  name: string
  type: string
  x: number
  y: number
  width: number
  height: number
  level: AlarmLevel
  enabled: boolean
  version: string
  appliesTo: string
  edgeSynced: boolean
}

export interface Device {
  id: string
  name: string
  type: string
  x: number
  y: number
  speed: number
  direction: string
  distance: number
  threshold: number
  status: EntityState
  sensorQuality: number
  controlState: string
  linkedPerson: string
}

export interface AiEvent {
  id: string
  type: string
  camera: string
  area: string
  confidence: number
  status: 'pending' | 'confirmed' | 'false_positive' | 'uncertain'
  occurredAt: string
  modelVersion: string
}

export interface Alarm {
  id: string
  title: string
  objectName: string
  area: string
  level: AlarmLevel
  source: string
  status: string
  owner: string
  occurredAt: string
  durationSeconds: number
  traceId: string
}

export interface Rule {
  id: string
  name: string
  domain: string
  level: AlarmLevel
  version: string
  enabled: boolean
  edgeSynced: boolean
}

export interface ServiceStatus {
  name: string
  state: 'healthy' | 'degraded' | 'offline'
  latencyMs: number
  detail: string
}

export interface Scenario {
  id: string
  name: string
  domain: string
  level: AlarmLevel
  duration: string
  description: string
}

export interface AuditEntry {
  id: string
  time: string
  action: string
  operator: string
  result: string
  traceId: string
}

export interface SystemStatus {
  cloudConnected: boolean
  edgeVersion: string
  cachedEvents: number
  availability: number
  services: ServiceStatus[]
}

export interface SafetySnapshot {
  schemaVersion: string
  updatedAt: string
  dataQuality: string
  workPlan: WorkPlan
  people: Person[]
  fences: Fence[]
  devices: Device[]
  aiEvents: AiEvent[]
  alarms: Alarm[]
  rules: Rule[]
  scenarios: Scenario[]
  auditLogs: AuditEntry[]
  system: SystemStatus
}

