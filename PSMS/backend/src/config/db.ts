import fs from 'node:fs';
import net from 'node:net';
import mongoose from 'mongoose';
import { MongoMemoryServer } from 'mongodb-memory-server';
import { env } from './env.js';
import { logger } from '../lib/logger.js';

/**
 * MongoDB 连接层。
 *
 * 两种运行模式（由 backend/.env 的 MONGODB_URI 决定）：
 *  1. MONGODB_URI 留空 → 内嵌模式：拉起一个**真实的 mongod 进程**
 *     （由 mongodb-memory-server 提供二进制），数据以 WiredTiger 引擎
 *     持久化到 backend/.data/mongodb，进程退出后数据保留，可重复启动。
 *  2. MONGODB_URI 有值 → 连接该外部 MongoDB 实例。
 *
 * 两种模式都通过 mongoose 建立正式连接，并使用真实的集合、索引与聚合能力。
 */

let memoryServer: MongoMemoryServer | null = null;
let connected = false;

/** 已解析出的实际连接串（用于日志与排查） */
let resolvedUri = '';

/** 隐藏连接串中的账号密码后再输出到日志 */
function maskUri(uri: string): string {
  return uri.replace(/\/\/([^:@/]+):([^@/]+)@/, '//$1:****@');
}

/** 启动内嵌 mongod，并返回其连接串 */
async function startEmbeddedMongod(): Promise<string> {
  // mongodb-memory-server 在创建实例时读取这些环境变量，必须先于 create() 设置
  process.env.MONGOMS_DOWNLOAD_DIR = env.MONGOMS_DOWNLOAD_DIR_ABS;
  process.env.MONGOMS_VERSION = env.MONGOMS_VERSION;

  fs.mkdirSync(env.MONGODB_DATA_DIR_ABS, { recursive: true });
  fs.mkdirSync(env.MONGOMS_DOWNLOAD_DIR_ABS, { recursive: true });

  logger.info(
    { dbPath: env.MONGODB_DATA_DIR_ABS, port: env.MONGODB_EMBEDDED_PORT, version: env.MONGOMS_VERSION },
    '正在启动内嵌 MongoDB（真实 mongod 进程）...',
  );

  memoryServer = await MongoMemoryServer.create({
    instance: {
      dbName: env.MONGODB_DB_NAME,
      // 指定 dbPath + WiredTiger，数据才会真正落盘（默认是临时目录，进程退出即丢）
      dbPath: env.MONGODB_DATA_DIR_ABS,
      storageEngine: 'wiredTiger',
      // 固定端口，便于用 mongosh / MongoDB Compass 直连排查
      port: env.MONGODB_EMBEDDED_PORT,
    },
  });

  return memoryServer.getUri(env.MONGODB_DB_NAME);
}

/** 建立 mongoose 连接 */
/** 探测端口是否有服务监听 */
export function isPortListening(port: number, host = '127.0.0.1'): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = net.connect({ port, host });
    const done = (result: boolean) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(result);
    };
    socket.setTimeout(800);
    socket.once('connect', () => done(true));
    socket.once('timeout', () => done(false));
    socket.once('error', () => done(false));
  });
}

/**
 * 建立 mongoose 连接。
 *
 * @param options.reuseExistingIfListening
 *   内嵌模式下，若目标端口已有 mongod 在监听（例如后端服务已在另一个终端运行），
 *   则直接复用该实例，避免重复启动 mongod 造成端口冲突。
 *   仅自检/统计类工具应开启此项；主服务默认关闭，以免误连到无关实例。
 */
export async function connectDatabase(options: {
  reuseExistingIfListening?: boolean;
} = {}): Promise<string> {
  if (connected) return resolvedUri;

  mongoose.set('strictQuery', true);
  // 关闭自动建集合：时序集合必须显式按 timeseries 选项创建，
  // 交给"首次写入隐式建集合"会被建成普通集合。
  // 常规集合由 MongoDB 在首次写入时隐式创建，无需 autoCreate。
  mongoose.set('autoCreate', false);

  if (env.USE_EMBEDDED_MONGODB) {
    const reuse =
      options.reuseExistingIfListening === true &&
      (await isPortListening(env.MONGODB_EMBEDDED_PORT));

    if (reuse) {
      resolvedUri = `mongodb://127.0.0.1:${env.MONGODB_EMBEDDED_PORT}/${env.MONGODB_DB_NAME}`;
      logger.info({ port: env.MONGODB_EMBEDDED_PORT }, '检测到已运行的 mongod，复用该实例');
    } else {
      resolvedUri = await startEmbeddedMongod();
    }
  } else {
    resolvedUri = env.MONGODB_URI;
  }

  await mongoose.connect(resolvedUri, {
    dbName: env.MONGODB_DB_NAME,
    // 索引由 models/index.ts 的 syncAllIndexes() 显式同步，避免并发建索引
    autoIndex: false,
    serverSelectionTimeoutMS: 30_000,
  });

  connected = true;
  logger.info({ uri: maskUri(resolvedUri), db: env.MONGODB_DB_NAME }, 'MongoDB 连接成功');
  return resolvedUri;
}

/** 断开连接，并（内嵌模式下）停止 mongod —— 数据保留在磁盘 */
export async function disconnectDatabase(): Promise<void> {
  if (connected) {
    await mongoose.disconnect();
    connected = false;
    logger.info('MongoDB 连接已关闭');
  }
  if (memoryServer) {
    // doCleanup: false —— 关键！否则会删除 dbPath 下的数据文件
    await memoryServer.stop({ doCleanup: false });
    memoryServer = null;
    logger.info({ dbPath: env.MONGODB_DATA_DIR_ABS }, '内嵌 mongod 已停止（数据已保留）');
  }
}

export function isConnected(): boolean {
  return connected;
}

/** 当前实际使用的连接串（已脱敏） */
export function getResolvedUri(): string {
  return maskUri(resolvedUri);
}

/** mongoose 原生连接对象，供需要底层 driver 的地方使用 */
export function getNativeDb() {
  if (!mongoose.connection.db) {
    throw new Error('MongoDB 尚未连接，无法获取底层 db 句柄');
  }
  return mongoose.connection.db;
}

export { mongoose };
