import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { User, type UserDoc } from '../db/tables.js';
import { env } from '../config/env.js';
import { AppError } from '../middleware/errorHandler.js';

export interface LoginResult {
  accessToken: string;
  refreshToken: string;
  user: {
    actorId: string;
    username: string;
    displayName: string;
    roleCode: string;
    dataScope: string[];
  };
}

function signOptions(expiresIn: string): jwt.SignOptions {
  return { expiresIn: expiresIn as jwt.SignOptions['expiresIn'] };
}

function buildPayload(user: UserDoc) {
  return {
    userId: user._id,
    actorId: user.actorId,
    roleCode: user.roleCode,
    dataScope: user.dataScope ?? [],
  };
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const user = await User.findOne({ username }).lean<UserDoc>();
  if (!user) throw new AppError(401, 'AUTH_FAILED', '用户名或密码错误');

  const valid = await bcrypt.compare(password, user.passwordHash);
  if (!valid) throw new AppError(401, 'AUTH_FAILED', '用户名或密码错误');

  await User.findByIdAndUpdate(user._id, { online: true, lastLoginAt: new Date() });

  const payload = buildPayload(user);
  const accessToken = jwt.sign(payload, env.JWT_SECRET, signOptions(env.JWT_EXPIRES_IN));
  const refreshToken = jwt.sign({ userId: payload.userId }, env.JWT_SECRET, signOptions(env.JWT_REFRESH_EXPIRES_IN));

  return {
    accessToken,
    refreshToken,
    user: {
      actorId: user.actorId,
      username: user.username,
      displayName: user.displayName,
      roleCode: user.roleCode,
      dataScope: user.dataScope ?? [],
    },
  };
}

export async function refreshAccessToken(token: string): Promise<{ accessToken: string }> {
  let decoded: { userId?: string };
  try {
    decoded = jwt.verify(token, env.JWT_SECRET) as { userId?: string };
  } catch {
    throw new AppError(401, 'TOKEN_EXPIRED', '刷新令牌无效或已过期');
  }

  if (!decoded.userId) throw new AppError(401, 'TOKEN_EXPIRED', '刷新令牌缺少用户标识');

  const user = await User.findById(decoded.userId).lean<UserDoc>();
  if (!user) throw new AppError(401, 'USER_NOT_FOUND', '用户不存在');

  return {
    accessToken: jwt.sign(buildPayload(user), env.JWT_SECRET, signOptions(env.JWT_EXPIRES_IN)),
  };
}

export async function getCurrentUser(userId: string): Promise<Omit<UserDoc, 'passwordHash'>> {
  const user = await User.findById(userId).lean<UserDoc>();
  if (!user) throw new AppError(404, 'USER_NOT_FOUND', '用户不存在');

  // 明确剔除密码散列，避免通过接口外泄
  const { passwordHash: _passwordHash, ...safe } = user;
  return safe;
}
