/**
 * 能源总览（驾驶舱）API 契约
 * REQ-057 至 062、019、053、029
 *
 * 2026-07-13：backend #14 收官 + 数据面冻结，真接口成为唯一路径；不再保留 mock 分支。
 * 历史 mock 模块 web/src/views/dashboard/mock.js 属 frontend-dev 目录内的
 * 联调期回退，本文件不再引用；如后续彻底清理可整体移除该模块。
 *
 * 契约字段来源：PRD §5.1 页面级设计（关键字段 KPI 卡表 + 布局描述）
 * 契约参考文档：docs/mock-contracts.md（总览契约 v0.1）
 */
import request from '@/utils/request'

/**
 * 拉取总览页数据（一次性聚合返回）
 * @param {Object} query
 * @param {'today'|'week'|'month'|'custom'} query.timeRange
 * @param {'ALL'|'A'|'B'} query.zone
 * @param {'ELEC'|'WATER'|'AIR'} query.energyType
 * @returns {Promise<{code:number,msg:string,data:OverviewPayload}>}
 *
 * OverviewPayload:
 * {
 *   signature: { version, formula, priceVersion, sigId, generatedAt, coverage },
 *   demoState: { enabled, hint, reqAnchor },
 *   kpis: KpiCard[],                        // 6 张
 *   trend: TrendPayload,                    // 24h 负荷 + 基线带
 *   topObjects: TopObject[],                // Top5
 *   alarms: AlarmItem[],                    // 最新告警滚动
 *   quality: QualityPayload,                // 数据质量摘要 + 48 信号阵列
 * }
 */
export function getOverview(query = {}) {
  return request({
    url: '/overview/summary',
    method: 'get',
    params: query
  })
}
