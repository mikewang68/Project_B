/**
 * 原始数据与质量 API 契约层（第二幕）
 * REQ-010–023 · REQ-062
 * 契约参考：docs/mock-contracts.md §2 v0.1
 *
 * 端点：
 *  GET  /raw-quality/summary            — 汇总（点位树 + 读数曲线 + 任务 + 补传 + 重算 + 质量分布）
 *  POST /raw-quality/backfill           — 触发补传（幂等，同点位+时间不重复入库，REQ-011/014）
 *  POST /raw-quality/recompute          — 触发重算（迟到/补采标记消费，REQ-012/020）
 *
 * 2026-07-14：backend #18 收官，真接口成为唯一路径；不再保留 mock 分支。
 * 历史 mock 模块 web/src/views/raw-quality/mock.js 属 frontend-dev 目录内的
 * 联调期回退，本文件不再引用；如后续彻底清理可整体移除该模块。
 */
import request from '@/utils/request'

/**
 * 拉取汇总数据
 * @param {Object} query
 * @param {string} query.pointId    默认 BC-A1（第二幕主案例）
 * @param {string} query.timeStart  ISO8601
 * @param {string} query.timeEnd    ISO8601
 * @param {'ALL'|'A'|'B'} query.zone
 */
export function getRawQualitySummary(query = {}) {
  return request({ url: '/raw-quality/summary', method: 'get', params: query })
}

/**
 * 触发补传（REQ-014 P0）
 * @param {Object} body
 * @param {string} body.pointId
 * @param {string} body.cacheStart
 * @param {string} body.cacheEnd
 * @param {boolean} [body.dryRun]  只返回计划、不落库
 */
export function postBackfill(body = {}) {
  return request({ url: '/raw-quality/backfill', method: 'post', data: body })
}

/**
 * 触发重算（REQ-012 / 020）
 * @param {Object} body
 * @param {string} body.periodKey  如 "2026-07-06D" / "2026-07M"
 * @param {'day'|'month'} body.scope
 */
export function postRecompute(body = {}) {
  return request({ url: '/raw-quality/recompute', method: 'post', data: body })
}
