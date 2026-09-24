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

/**
 * 存储配置遵循组内基线的「多存储各司其职」分工
 * （见 EHMS/docs/EHM-architecture-baseline.md）：
 *   - openGauss  ：事务/业务数据（计划、工单、任务、人员、设备、异常、联锁、预约、离线包、配置、审计）
 *   - openGemini ：时序/遥测数据（设备遥测采集）
 * 变量命名与 SCS / EHMS 保持一致（OPENGAUSS_* / OPENGEMINI_*）。
 */
const envSchema = z.object({
  // ---------- openGauss（业务主库） ----------
  OPENGAUSS_HOST: z.string().trim().min(1).default('127.0.0.1'),
  OPENGAUSS_PORT: z.coerce.number().int().min(1).max(65535).default(5432),
  OPENGAUSS_DATABASE: z.string().trim().min(1).default('psms'),
  OPENGAUSS_USERNAME: z.string().trim().min(1).default('psms'),
  OPENGAUSS_PASSWORD: z.string().default(''),
  /** 组内 openGauss 6.0 驱动与 PostgreSQL 线协议对齐，schema 默认 public */
  OPENGAUSS_SCHEMA: z.string().trim().min(1).default('public'),
  OPENGAUSS_POOL_MAX: z.coerce.number().int().min(1).max(200).default(10),
  OPENGAUSS_POOL_MIN: z.coerce.number().int().min(0).max(50).default(2),
  OPENGAUSS_CONNECT_TIMEOUT_MS: z.coerce.number().int().min(500).default(8000),
  OPENGAUSS_IDLE_TIMEOUT_MS: z.coerce.number().int().min(1000).default(30_000),
  OPENGAUSS_STATEMENT_TIMEOUT_MS: z.coerce.number().int().min(0).default(15_000),
  /** 是否在启动时校验并补齐表结构（幂等 DDL） */
  OPENGAUSS_AUTO_MIGRATE: z
    .enum(['true', 'false'])
    .default('true')
    .transform((v) => v === 'true'),

  // ---------- openGemini（时序库） ----------
  /** dev 默认开启（本机经隧道可用）；server 环境按部署情况设置 */
  OPENGEMINI_ENABLED: z
    .enum(['true', 'false'])
    .default('true')
    .transform((v) => v === 'true'),
  OPENGEMINI_URL: z.string().trim().min(1).default('http://127.0.0.1:8086'),
  OPENGEMINI_DATABASE: z.string().trim().min(1).default('psms_telemetry'),
  OPENGEMINI_USERNAME: z.string().default(''),
  OPENGEMINI_PASSWORD: z.string().default(''),
  OPENGEMINI_TIMEOUT_MS: z.coerce.number().int().min(200).default(5000),
  /** 时序写入总开关；关闭时仅保留读取与探针 */
  OPENGEMINI_WRITE_ENABLED: z
    .enum(['true', 'false'])
    .default('true')
    .transform((v) => v === 'true'),

  // ---------- 启动行为 ----------
  /** 启动时若业务表为空则自动灌入种子数据 */
  AUTO_SEED: z
    .enum(['true', 'false'])
    .default('true')
    .transform((v) => v === 'true'),

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
  /** 业务库连接串（仅用于日志脱敏展示，不直接给 pg 使用） */
  get OPENGAUSS_DSN(): string {
    const { OPENGAUSS_HOST: h, OPENGAUSS_PORT: p, OPENGAUSS_DATABASE: d, OPENGAUSS_USERNAME: u } = raw;
    return `postgresql://${u}@${h}:${p}/${d}`;
  },
  /** openGemini 是否配置了写入所需的库名 */
  get OPENGEMINI_READY(): boolean {
    return raw.OPENGEMINI_ENABLED && raw.OPENGEMINI_DATABASE.length > 0;
  },
};

export const isDev = env.NODE_ENV === 'development';
export const isProd = env.NODE_ENV === 'production';
export const isTest = env.NODE_ENV === 'test';
