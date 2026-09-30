/**
 * 日志统计 / 智能诊断 —— 前端数据结构（与后端 DTO 对齐）。
 */

export interface NameCount {
  name: string
  total: number
  fail: number
  /** 失败率（0~100） */
  failureRate: number
}

export interface LogStatistics {
  total: number
  success: number
  fail: number
  /** 失败率（0~100） */
  failureRate: number
  byModule: NameCount[]
  byUser: NameCount[]
  byResult: NameCount[]
}

export type DiagSeverity = 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'

export interface LogDiagnosisFinding {
  ruleId: string
  severity: DiagSeverity
  title: string
  description: string
  evidenceLogIds: string[]
  recommendation: string
}

export interface DiagnosisTimeRange {
  from: string
  to: string
}

/** 诊断汇总：failureRate 为 0~1 小数（与统计接口的百分比不同，按后端 §23 口径） */
export interface DiagnosisSummary {
  total: number
  success: number
  failed: number
  failureRate: number
}

export interface LogDiagnosisReport {
  timeRange: DiagnosisTimeRange
  summary: DiagnosisSummary
  findings: LogDiagnosisFinding[]
}

export interface DiagQuery {
  from?: string
  to?: string
  user?: string
  module?: string
  result?: string
}
