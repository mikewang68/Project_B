import type { Request } from 'express';

/**
 * HTTP 参数读取助手。
 *
 * Express 5 的 `req.params` / `req.query` 在类型上可能是 `string | string[]`，
 * 直接传给业务函数会触发类型错误。这里统一收敛为单个字符串，
 * 避免各路由重复写类型断言。
 */

/** 读查询参数，取第一个值 */
export function q(req: Request, key: string): string | undefined {
  const value = req.query[key];
  if (Array.isArray(value)) {
    const first = value[0];
    return typeof first === 'string' ? first : undefined;
  }
  return typeof value === 'string' ? value : undefined;
}

/** 读查询参数并转数字，空值返回 undefined */
export function qNum(req: Request, key: string): number | undefined {
  const value = q(req, key);
  if (value === undefined || value === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/** 读逗号分隔的查询参数并拆成数组 */
export function qList(req: Request, key: string): string[] | undefined {
  const value = q(req, key);
  if (!value) return undefined;
  const items = value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  return items.length ? items : undefined;
}

/** 读路径参数（默认取 :id） */
export function pathParam(req: Request, key = 'id'): string {
  const value = (req.params as Record<string, string | string[] | undefined>)[key];
  if (Array.isArray(value)) return value[0] ?? '';
  return value ?? '';
}
