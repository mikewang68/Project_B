/**
 * MongoDB 连接自检 / 集合统计工具。
 *
 * 用法：pnpm db:stats
 *
 * 作用：真实连接一次数据库，打印连接模式、mongod 版本、
 *       各集合的文档数量与索引数量，用于确认数据库确实在工作。
 */
import { connectDatabase, disconnectDatabase, mongoose } from '../config/db.js';
import { env } from '../config/env.js';
import { syncAllIndexes } from '../models/index.js';

async function main(): Promise<void> {
  console.log('========================================');
  console.log(' MongoDB 连接自检');
  console.log('========================================');
  console.log(` 连接模式 : ${env.USE_EMBEDDED_MONGODB ? '内嵌 mongod（持久化）' : '外部实例'}`);
  console.log(` 数据目录 : ${env.USE_EMBEDDED_MONGODB ? env.MONGODB_DATA_DIR_ABS : '—'}`);
  console.log(` 目标库   : ${env.MONGODB_DB_NAME}`);
  console.log('');

  const uri = await connectDatabase({ reuseExistingIfListening: true });
  const db = mongoose.connection.db;
  if (!db) throw new Error('未能获取数据库句柄');

  const buildInfo = await db.admin().serverInfo();
  console.log(` mongod 版本 : ${buildInfo.version}`);
  console.log(` 连接串      : ${uri.replace(/\/\/([^:@/]+):([^@/]+)@/, '//$1:****@')}`);
  console.log('');

  await syncAllIndexes();

  const collections = await db.listCollections().toArray();
  console.log(` 集合数量 : ${collections.length}`);
  console.log('');
  console.log(' 集合名                       文档数   索引数  类型');
  console.log(' ---------------------------------------------------------');

  let totalDocs = 0;
  for (const info of collections.sort((a, b) => a.name.localeCompare(b.name))) {
    const count = await db.collection(info.name).countDocuments();
    const indexes = await db.collection(info.name).indexes();
    totalDocs += count;
    const isTs = (info as { type?: string }).type === 'timeseries';
    console.log(
      ` ${info.name.padEnd(28)} ${String(count).padStart(6)}   ${String(indexes.length).padStart(5)}   ${
        isTs ? '时序集合' : '普通集合'
      }`,
    );
    if (process.argv.includes('--indexes')) {
      for (const idx of indexes) {
        const ttl = typeof idx.expireAfterSeconds === 'number' ? ` TTL=${idx.expireAfterSeconds}s` : '';
        console.log(`     · ${idx.name} ${JSON.stringify(idx.key)}${ttl}`);
      }
    }
  }
  console.log(' ---------------------------------------------------------');
  console.log(` 文档总数 : ${totalDocs}`);
  console.log('');

  await disconnectDatabase();
  console.log('✅ 自检完成');
}

main().catch(async (err) => {
  console.error('❌ 自检失败:', err);
  await disconnectDatabase().catch(() => undefined);
  process.exit(1);
});
