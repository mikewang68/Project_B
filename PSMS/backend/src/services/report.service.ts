/**
 * 统计报表服务。
 *
 * 分组统计下沉到数据库侧执行（SQL GROUP BY），而不是把全量行拉到应用层再统计 ——
 * 这是关系库相对文档库/文件存储最直接的收益之一：聚合在库内完成，传输量恒定。
 * 原先的 MongoDB 聚合管道（$match/$group/$sort）在此翻译为标准 SQL。
 */
import { getPool } from '../db/openGaussClient.js';
import { beijingToday, reportPeriod } from './report-period.js';

export interface ReportQuery {
  startDate?: string;
  endDate?: string;
  workArea?: string;
  workAreas?: readonly string[];
  type?: string;
}

export interface GroupCount {
  _id: string;
  count: number;
}

interface WhereClause {
  sql: string;
  params: unknown[];
}

/** 时间区间 + 作业区的公共过滤条件（统一按 created_at 归集） */
function buildCommonWhere(query: ReportQuery): WhereClause {
  const parts: string[] = ['valid = 1'];
  const params: unknown[] = [];

  if (query.workArea) {
    params.push(query.workArea);
    parts.push(`work_area = $${params.length}`);
  } else if (query.workAreas) {
    params.push(query.workAreas);
    parts.push(`work_area = ANY($${params.length}::text[])`);
  }
  const period = reportPeriod(query.startDate ?? beijingToday(), query.endDate ?? beijingToday());
  params.push(period.from); parts.push(`created_at >= $${params.length}`);
  params.push(period.until); parts.push(`created_at < $${params.length}`);

  return {
    sql: parts.length > 0 ? `WHERE ${parts.join(' AND ')}` : '',
    params,
  };
}

/** 单列分组计数 */
async function groupCountBy(
  table: string,
  where: WhereClause,
  column: string,
): Promise<GroupCount[]> {
  const res = await getPool().query<{ key: string; count: string }>(
    `SELECT ${column} AS key, COUNT(*) AS count
       FROM ${table} ${where.sql}
      GROUP BY ${column}
      ORDER BY count DESC, key ASC`,
    where.params as never[],
  );
  return res.rows.map((r) => ({ _id: r.key, count: Number(r.count) }));
}

/** 二维分组（异常按 类型 × 等级） */
async function groupCountByTwo(
  table: string,
  where: WhereClause,
  colA: string,
  colB: string,
): Promise<GroupCount[]> {
  const res = await getPool().query<{ a: string; b: string; count: string }>(
    `SELECT ${colA} AS a, ${colB} AS b, COUNT(*) AS count
       FROM ${table} ${where.sql}
      GROUP BY ${colA}, ${colB}
      ORDER BY count DESC`,
    where.params as never[],
  );
  return res.rows.map((r) => ({ _id: `${r.a}_${r.b}`, count: Number(r.count) }));
}

export async function generateReport(query: ReportQuery) {
  const where = buildCommonWhere(query);

  const [plans, workOrders, equipments, exceptions] = await Promise.all([
    groupCountBy('plans', where, 'status'),
    groupCountBy('work_orders', where, 'status'),
    // 设备台账不按创建时间做期间统计，改为反映当前状态分布
    groupCountBy('equipment', query.workArea
      ? {sql:'WHERE valid = 1 AND work_area = $1', params:[query.workArea]}
      : query.workAreas ? {sql:'WHERE valid = 1 AND work_area = ANY($1::text[])',params:[query.workAreas]}
      : {sql:'WHERE valid = 1',params:[]}, 'status'),
    groupCountByTwo('exceptions', where, 'type', 'severity'),
  ]);

  return {
    period: {
      start: query.startDate,
      end: query.endDate,
      type: query.type ?? 'daily',
      timeZone: 'Asia/Shanghai',
      workArea: query.workArea ?? null,
      workAreas: query.workAreas ?? null,
    },
    plans,
    workOrders,
    equipments,
    exceptions,
    generatedAt: new Date().toISOString(),
  };
}
