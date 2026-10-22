import fs from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire('/opt/project-b/PSMS/backend/package.json');
const { Client } = require('pg');
const dotenv = require('dotenv');
const cfg = dotenv.parse(fs.readFileSync('/etc/project-b/PSMS/service.env'));
const base = '/opt/project-b/PSMS/backend';
const staged = process.argv[2];
const backup = process.argv[3];
if (cfg.OPENGAUSS_DATABASE !== 'psms') throw new Error('Refusing to touch a non-PSMS database');
const client = new Client({ host: cfg.OPENGAUSS_HOST, port: Number(cfg.OPENGAUSS_PORT || 5432), database: cfg.OPENGAUSS_DATABASE, user: cfg.OPENGAUSS_USERNAME, password: cfg.OPENGAUSS_PASSWORD });
await client.connect();
const old = await import(`${base}/dist/db/schema.js`);
const next = await import(`${staged}/dist/db/schema.js`);
const quote = (name) => '"' + name.replaceAll('"', '""') + '"';
try {
  await client.query('BEGIN ISOLATION LEVEL REPEATABLE READ');
  const snapshot = { database: 'psms', capturedAt: new Date().toISOString(), ddl: old.SCHEMA_STATEMENTS, tables: {} };
  for (const table of old.ALL_TABLES) snapshot.tables[table] = (await client.query(`SELECT * FROM public.${quote(table)}`)).rows;
  snapshot.columns = (await client.query("SELECT * FROM information_schema.columns WHERE table_schema = 'public'")).rows;
  snapshot.indexes = (await client.query("SELECT * FROM pg_indexes WHERE schemaname = 'public'")).rows;
  await client.query('COMMIT');
  fs.writeFileSync(backup, JSON.stringify(snapshot), { mode: 0o600 });
  console.log('PSMS database snapshot saved: ' + old.ALL_TABLES.length + ' tables');
  await client.query('BEGIN');
  await client.query('CREATE SCHEMA psms_release_trial_20261012');
  await client.query('SET LOCAL search_path TO psms_release_trial_20261012');
  const trialSchema = old.SCHEMA_STATEMENTS.filter((sql) => !/^ALTER TABLE audit_logs ADD COLUMN (writelog_state|writelog_id|chain_state|chain_tx_id|chain_error)/.test(sql));
  await client.query(trialSchema.join(';\n') + ';');
  for (const table of old.ALL_TABLES) for (const row of snapshot.tables[table]) {
    const names = Object.keys(row);
    const values = names.map((name) => row[name] !== null && typeof row[name] === 'object' && !Array.isArray(row[name]) && !(row[name] instanceof Date) ? JSON.stringify(row[name]) : row[name]);
    await client.query(`INSERT INTO ${quote(table)} (${names.map(quote).join(',')}) VALUES (${names.map((_, i) => '$' + (i + 1)).join(',')})`, values);
  }
  const columns = (await client.query('SELECT table_name,column_name FROM information_schema.columns WHERE table_schema = current_schema()')).rows;
  const existing = new Set(columns.map((row) => row.table_name + '.' + row.column_name));
  const repeated = next.SCHEMA_STATEMENTS.filter((sql) => { const m = sql.match(/^ALTER TABLE (\w+) ADD COLUMN (\w+)/); return !m || !existing.has(m[1] + '.' + m[2]); });
  await client.query(repeated.join(';\n') + ';');
  for (const table of old.ALL_TABLES) {
    const rows = (await client.query(`SELECT COUNT(*) AS count FROM ${quote(table)} WHERE valid = 1`)).rows;
    if (Number(rows[0].count) !== snapshot.tables[table].length) throw new Error('Migration row count mismatch: ' + table);
  }
  if (snapshot.tables.plans.length) {
    await client.query('UPDATE plans SET valid = 0 WHERE id = $1', [snapshot.tables.plans[0].id]);
    const row = (await client.query('SELECT COUNT(*) AS total, SUM(CASE WHEN valid = 1 THEN 1 ELSE 0 END) AS active FROM plans')).rows[0];
    if (Number(row.total) !== snapshot.tables.plans.length || Number(row.active) !== snapshot.tables.plans.length - 1) throw new Error('Logical deletion did not preserve the record');
  }
  await client.query('ROLLBACK');
  console.log('PASS: migration, repeated migration, preserved rows and valid=0 logical deletion in rolled-back PSMS trial schema.');
} catch (error) { await client.query('ROLLBACK').catch(() => {}); throw error; }
finally { await client.end(); }
