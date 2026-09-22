import type { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import { env } from '../config/env.js';

export interface JwtPayload {
  userId: string;
  actorId: string;
  roleCode: string;
  dataScope: string[];
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      user?: JwtPayload;
    }
  }
}

/** 强制鉴权：缺少或非法令牌一律 401 */
export function authenticate(req: Request, res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    res.status(401).json({ ok: false, errorCode: 'UNAUTHORIZED', message: '未提供有效的认证令牌' });
    return;
  }

  const token = header.slice(7);
  try {
    req.user = jwt.verify(token, env.JWT_SECRET) as JwtPayload;
    next();
  } catch {
    res.status(401).json({ ok: false, errorCode: 'UNAUTHORIZED', message: '认证令牌无效或已过期' });
  }
}

/** 可选鉴权：有令牌则解析，无令牌也放行（用于只读接口） */
export function optionalAuth(req: Request, _res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  if (header?.startsWith('Bearer ')) {
    try {
      req.user = jwt.verify(header.slice(7), env.JWT_SECRET) as JwtPayload;
    } catch {
      // 令牌无效时按匿名处理，不阻断只读请求
    }
  }
  next();
}

/**
 * 动作权限校验（当前为角色白名单简化实现）。
 *
 * 说明：后端角色集（super_admin / scheduler / ...）尚未与前端契约的
 * 13 个 RoleCode（DISPATCHER / SAFETY / AUDITOR ...）对齐，
 * 因此这里只做管理员的粗粒度放行，细粒度权限码校验留在前端。
 * 待角色体系统一后，应替换为基于权限码的判定。
 */
export function requirePermission(...permissions: string[]): (req: Request, res: Response, next: NextFunction) => void {
  return (req, res, next) => {
    if (!req.user) {
      res.status(401).json({ ok: false, errorCode: 'UNAUTHORIZED', message: '未认证' });
      return;
    }
    if (!permissions.length) {
      next();
      return;
    }
    const allowedRoles = ['super_admin', 'admin'];
    if (allowedRoles.includes(req.user.roleCode)) {
      next();
      return;
    }
    res.status(403).json({ ok: false, errorCode: 'FORBIDDEN', message: '权限不足，无法执行此操作' });
  };
}
