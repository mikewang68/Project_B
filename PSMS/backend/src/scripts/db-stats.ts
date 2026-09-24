/**
 * 存储自检：一条命令看清 openGauss + openGemini 的真实状态。
 *
 * 用法：
 *   pnpm db:stats            概述：连接、版本、表与行数、索引数
 *   pnpm db:stats --tables   额外打印每张表的列清单
 *   pnpm db:stats --indexes  额外打印索引明细
 */
import { closePool, getPool } from '../db/openGaussClient.js';
import { env } from '../config/env.js';
import { probe, query as geminiQuery } from '../db/openGeminiClient.js';
import { ALL_TABLES } from '../db/schema.js';

const args = new Set(process.argv.slice(2));
const showColumns = args.has('--tables');
const showIndexes = args.has('--indexes');

interface TableInfo {
  name: string;
  rows: number;
  columns: number;
  indexes: number;
  comment: string;
}

async function inspectOpenGauss(): Promise<TableInfo[]> {
  const pool = getPool();

  console.log('=== openGauss（业务主库） ===');
  const info = await pool.query<{ version: string; enc: string; db: string; user: string }>(
    `SELECT version() AS version,
            current_setting('server_encoding') AS enc,
            current_database() AS db,
            current_user AS user`,
  );
  const i = info.rows[0];
  console.log(`  连接      : ${i.user}@${env.OPENGAUSS_HOST}:${env.OPENGAUSS_PORT}/${i.db}`);
  console.log(`  版本      : ${i.version.split(' ').slice(0, 4).join(' ')}`);
  console.log(`  字符集    : ${i.enc}`);

  const cols = await pool.query<{ table_name: string; n: string }>(
    `SELECT table_name, COUNT(*) AS n FROM information_schema.columns
      WHERE table_schema = current_schema() GROUP BY table_name`,
  );
  const colMap = new Map(cols.rows.map((r) => [r.table_name, Number(r.n)]));

  const idx = await pool.query<{ tablename: string; n: string }>(
    `SELECT tablename, COUNT(*) AS n FROM pg_indexes
      WHERE schemaname = current_schema() GROUP BY tablename`,
  );
  const idxMap = new Map(idx.rows.map((r) => [r.tablename, Number(r.n)]));

  const cmt = await pool.query<{ relname: string; description: string }>(
    `SELECT c.relname, d.description
       FROM pg_class c
       JOIN pg_description d ON d.objoid = c.oid AND d.objsubid = 0
       JOIN pg_namespace n ON n.oid = c.relnamespace
      WHERE n.nspname = current_schema() AND c.relkind = 'r'`,
  );
  const cmtMap = new Map(cmt.rows.map((r) => [r.relname, r.description]));

  const tables: TableInfo[] = [];
  for (const name of ALL_TABLES) {
    const r = await pool.query<{ n: string }>(`SELECT COUNT(*) AS n FROM ${name}`);
    tables.push({
      name,
      rows: Number(r.rows[0].n),
      columns: colMap.get(name) ?? 0,
      indexes: idxMap.get(name) ?? 0,
      comment: cmtMap.get(name) ?? '',
    });
  }

  console.log();
  console.log('  表名                        行数   列数  索引  说明');
  console.log('  ' + '-'.repeat(78));
  let totalRows = 0;
  for (const t of tables) {
    totalRows += t.rows;
    console.log(
      `  ${t.name.padEnd(26)} ${String(t.rows).padStart(6)} ${String(t.columns).padStart(5)} `
      + `${String(t.indexes).padStart(5)}  ${t.comment.slice(0, 26)}`,
    );
  }
  console.log('  ' + '-'.repeat(78));
  console.log(`  合计 ${tables.length} 张表 / ${totalRows} 行`);

  if (showColumns) {
    console.log();
    console.log('=== 列清单 ===');
    for (const name of ALL_TABLES) {
      const rows = await pool.query<{ column_name: string; data_type: string; is_nullable: string }>(
        `SELECT column_name, data_type, is_nullable FROM information_schema.columns
          WHERE table_schema = current_schema() AND table_name = $1 ORDER BY ordinal_position`,
        [name],
      );
      console.log(`  [${name}]`);
      for (const c of rows.rows) {
        console.log(
          `     ${c.column_name.padEnd(24)} ${c.data_type.padEnd(24)} `
          + `${c.is_nullable === 'NO' ? 'NOT NULL' : ''}`,
        );
      }
    }
  }

  if (showIndexes) {
    console.log();
    console.log('=== 索引明细 ===');
    for (const name of ALL_TABLES) {
      const rows = await pool.query<{ indexname: string; indexdef: string }>(
        `SELECT indexname, indexdef FROM pg_indexes
          WHERE schemaname = current_schema() AND tablename = $1 ORDER BY indexname`,
        [name],
      );
      console.log(`  [${name}]`);
      for (const r of rows.rows) {
        const using = r.indexdef.split(' USING ')[1] ?? '';
        console.log(`     ${r.indexname.padEnd(40)} ${using.slice(0, 80)}`);
      }
    }
  }

  return tables;
}

async function inspectOpenGemini(): Promise<void> {
  console.log();
  console.log('=== openGemini（时序库） ===');
  const p = await probe();
  console.log(`  地址      : ${p.url}`);
  console.log(`  开关      : ${p.enabled ? 'enabled' : 'disabled'}`);
  console.log(`  可达      : ${p.reachable ? `是（${p.latencyMs}ms）` : `否 — ${p.error}`}`);
  if (p.version) console.log(`  版本      : ${p.version}`);
  console.log(`  目标库    : ${p.database}（${p.databaseExists ? '已存在' : '不存在'}）`);
  if (!p.reachable) return;

  const dbs = await geminiQuery('SHOW DATABASES');
  const names = (dbs.series[0]?.values ?? []).map((row) => String(row[0]));
  console.log(`  库列表    : ${names.join(', ')}`);

  if (!p.databaseExists) {
    console.log('  （库尚未创建：启动后端或执行 pnpm db:init 会自动创建）');
    return;
  }

  const ms = await geminiQuery('SHOW MEASUREMENTS', p.database);
  const list = (ms.series[0]?.values ?? []).map((row) => String(row[0]));
  console.log(`  measurement (${list.length}) : ${list.join(', ') || '(空)'}`);

  for (const name of list) {
    const cnt = await geminiQuery(`SELECT COUNT(*) FROM "${name}"`, p.database);
    const n = cnt.series[0]?.values?.[0]?.[1];
    const tagKeys = await geminiQuery(`SHOW TAG KEYS FROM "${name}"`, p.database);
    const fieldKeys = await geminiQuery(`SHOW FIELD KEYS FROM "${name}"`, p.database);
    console.log(`     ${name}: ${n} 点`);
    console.log(`        tags  : ${(tagKeys.series[0]?.values ?? []).map((r) => String(r[0])).join(', ') || '-'}`);
    console.log(`        fields: ${(fieldKeys.series[0]?.values ?? []).map((r) => String(r[0])).join(', ') || '-'}`);
  }
}

async function main(): Promise<void> {
  console.log(`存储自检  ${new Date().toISOString()}`);
  console.log();
  try {
    await inspectOpenGauss();
  } catch (err) {
    console.error('  ❌ openGauss 检查失败:', (err as Error).message);
    console.error('     确认隧道已建立: ssh -N -L 5432:192.168.101.57:5432 lrz@100.65.200.125');
  }
  try {
    await inspectOpenGemini();
  } catch (err) {
    console.error('  ❌ openGemini 检查失败:', (err as Error).message);
    console.error('     确认隧道已建立: ssh -N -L 8086:192.168.101.74:8086 lrz@100.65.200.125');
  }
  await closePool();
}

main().catch(async (err) => {
  console.error('自检异常:', err);
  await closePool().catch(() => undefined);
  process.exit(1);
});
