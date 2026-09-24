/**
 * 统计报表服务。
 *
 * 分组统计下沉到数据库侧执行（SQL GROUP BY），而不是把全量行拉到应用层再统计 ——
 * 这是关系库相对文档库/文件存储最直接的收益之一：聚合在库内完成，传输量恒定。
 * 原先的 MongoDB 聚合管道（$match/$group/$sort）在此翻译为标准 SQL。
 */
import { getPool } from '../db/openGaussClient.js';

export interface ReportQuery {
  startDate?: string;
  endDate?: string;
  workArea?: string;
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
  const parts: string[] = [];
  const params: unknown[] = [];

  if (query.workArea) {
    params.push(query.workArea);
    parts.push(`work_area = $${params.length}`);
  }
  if (query.startDate) {
    params.push(new Date(query.startDate));
    parts.push(`created_at >= $${params.length}`);
  }
  if (query.endDate) {
    const end = new Date(query.endDate);
    end.setDate(end.getDate() + 1);
    params.push(end);
    parts.push(`created_at <= $${params.length}`);
  }

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
    groupCountBy('equipment', { sql: '', params: [] }, 'status'),
    groupCountByTwo('exceptions', where, 'type', 'severity'),
  ]);

  return {
    period: {
      start: query.startDate,
      end: query.endDate,
      type: query.type ?? 'daily',
    },
    plans,
    workOrders,
    equipments,
    exceptions,
    generatedAt: new Date().toISOString(),
  };
}
