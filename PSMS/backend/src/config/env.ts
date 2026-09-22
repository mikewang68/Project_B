import { fileURLToPath } from 'node:url';
import path from 'node:path';
import dotenv from 'dotenv';
import { z } from 'zod';

/**
 * 后端工程根目录。
 * 无论是 tsx 运行 src/ 还是 node 运行 dist/，上两级都指向 backend/。
 */
export const BACKEND_ROOT = fileURLToPath(new URL('../../', import.meta.url));

dotenv.config({ path: path.join(BACKEND_ROOT, '.env') });

/** 把配置里的相对路径统一解析为基于 backend/ 的绝对路径 */
function resolveFromRoot(value: string): string {
  return path.isAbsolute(value) ? value : path.resolve(BACKEND_ROOT, value);
}

const envSchema = z.object({
  // ---------- MongoDB ----------
  /** 留空表示使用内嵌 mongod */
  MONGODB_URI: z.string().trim().default(''),
  MONGODB_DB_NAME: z.string().trim().min(1).default('psms'),
  MONGODB_EMBEDDED_PORT: z.coerce.number().int().min(1).max(65535).default(27017),
  MONGODB_DATA_DIR: z.string().trim().min(1).default('.data/mongodb'),
  MONGOMS_DOWNLOAD_DIR: z.string().trim().min(1).default('.cache/mongodb-binaries'),
  MONGOMS_VERSION: z.string().trim().min(1).default('7.0.14'),
  AUTO_SEED: z
    .enum(['true', 'false'])
    .default('true')
    .transform((value) => value === 'true'),

  // ---------- JWT ----------
  JWT_SECRET: z.string().min(16),
  JWT_EXPIRES_IN: z.string().default('8h'),
  JWT_REFRESH_EXPIRES_IN: z.string().default('7d'),

  // ---------- Server ----------
  PORT: z.coerce.number().int().min(1).max(65535).default(3100),
  NODE_ENV: z.enum(['development', 'production', 'test']).default('development'),
  CORS_ORIGIN: z.string().default('http://localhost:5173'),
  LOG_LEVEL: z
    .enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace'])
    .default('info'),
});

const parsed = envSchema.safeParse(process.env);

if (!parsed.success) {
  // 配置错误必须在进程启动阶段就暴露，不能静默降级
  console.error('❌ 环境配置校验失败:', parsed.error.flatten().fieldErrors);
  process.exit(1);
}

const raw = parsed.data;

export const env = {
  ...raw,
  /** 内嵌 mongod 数据目录绝对路径 */
  MONGODB_DATA_DIR_ABS: resolveFromRoot(raw.MONGODB_DATA_DIR),
  /** mongod 二进制缓存目录绝对路径 */
  MONGOMS_DOWNLOAD_DIR_ABS: resolveFromRoot(raw.MONGOMS_DOWNLOAD_DIR),
  /** 是否使用内嵌 mongod */
  USE_EMBEDDED_MONGODB: raw.MONGODB_URI.length === 0,
};

export const isDev = env.NODE_ENV === 'development';
export const isProd = env.NODE_ENV === 'production';
export const isTest = env.NODE_ENV === 'test';
