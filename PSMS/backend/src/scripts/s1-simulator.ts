/**
 * S1 设备采集模拟守护进程。
 * 数据字段依据《B项目-接口前置清单全量汇总》中的“设备采集点”约定：
 * equipmentId / pointCode / value / quality / sourceTimestamp / unit。
 * 默认每秒写入一批；--once 只写一批，便于联调。
 */
import { env } from '../config/env.js';
import { TelemetryPoint } from '../db/telemetry.js';

const once = process.argv.includes('--once');
const intervalMs = Math.max(200, Number(process.env.PSMS_SIMULATOR_INTERVAL_MS ?? 1000));
const points = [
  { equipmentId: 'EQ-IMG-01', pointCode: 'LOAD', base: 38, unit: 't', amplitude: 1.5 },
  { equipmentId: 'EQ-IMG-01', pointCode: 'TROLLEY_POS', base: 12.5, unit: 'm', amplitude: 0.8 },
  { equipmentId: 'EQ-IMG-05', pointCode: 'WEIGHT', base: 34.2, unit: 't', amplitude: 0.6 },
  { equipmentId: 'EQ-IMG-06', pointCode: 'LEVEL', base: 62, unit: '%', amplitude: 1.2 },
];
let sequence = 0;

async function sendBatch(): Promise<number> {
  const now = Date.now(); sequence += 1;
  const docs = points.map((point, index) => ({
    ...point,
    value: Number((point.base + Math.sin(sequence / 4 + index) * point.amplitude).toFixed(3)),
    quality: 'GOOD', sourceTimestamp: new Date(now), metadata: { sourceSystem: 'S1-SIMULATOR', seq: sequence },
  }));
  return TelemetryPoint.insertMany(docs);
}

if (!env.OPENGEMINI_ENABLED) throw new Error('OPENGEMINI_ENABLED=false，无法启动 S1 模拟守护进程');
const run = async () => {
  const written = await sendBatch();
  console.log(JSON.stringify({ module: 'S1', sourceSystem: 'S1-SIMULATOR', written, at: new Date().toISOString() }));
};
await run();
if (!once) {
  const timer = setInterval(() => { void run().catch((error) => console.error(JSON.stringify({ module: 'S1', error: error instanceof Error ? error.message : '写入失败' }))); }, intervalMs);
  for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => { clearInterval(timer); process.exit(0); });
}
