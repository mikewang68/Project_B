import type { Model } from 'mongoose';
import { Equipment, Exception, Plan, WorkOrder } from '../models/index.js';

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

/**
 * 按指定字段做分组计数。
 *
 * 使用 MongoDB 聚合管道在数据库侧完成分组，而不是把全量文档拉到
 * 应用层再统计 —— 这是文档数据库相对文件存储的核心优势之一。
 */
async function groupCountBy(
  model: Model<unknown>,
  match: Record<string, unknown>,
  field: string,
): Promise<GroupCount[]> {
  return model.aggregate<GroupCount>([
    { $match: match },
    { $group: { _id: `$${field}`, count: { $sum: 1 } } },
    { $sort: { count: -1, _id: 1 } },
  ]);
}

/** 时间区间过滤条件（按 createdAt 归集） */
function timeRangeMatch(query: ReportQuery): Record<string, unknown> {
  const match: Record<string, unknown> = {};
  if (query.workArea) match.workArea = query.workArea;
  if (query.startDate || query.endDate) {
    const range: Record<string, Date> = {};
    if (query.startDate) range.$gte = new Date(query.startDate);
    if (query.endDate) {
      const end = new Date(query.endDate);
      end.setDate(end.getDate() + 1);
      range.$lte = end;
    }
    match.createdAt = range;
  }
  return match;
}

export async function generateReport(query: ReportQuery) {
  const match = timeRangeMatch(query);

  const [plans, workOrders, equipments, exceptionGroups] = await Promise.all([
    groupCountBy(Plan as unknown as Model<unknown>, match, 'status'),
    groupCountBy(WorkOrder as unknown as Model<unknown>, match, 'status'),
    groupCountBy(Equipment as unknown as Model<unknown>, match, 'status'),
    // 异常按 类型 + 等级 二维聚合
    Exception.aggregate<{ _id: { type: string; severity: string }; count: number }>([
      { $match: match },
      { $group: { _id: { type: '$type', severity: '$severity' }, count: { $sum: 1 } } },
      { $sort: { count: -1 } },
    ]),
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
    exceptions: exceptionGroups.map((item) => ({
      _id: `${item._id.type}_${item._id.severity}`,
      count: item.count,
    })),
    generatedAt: new Date().toISOString(),
  };
}
