/**
 * 端到端冒烟测试（真实 HTTP + 真实 openGauss + 真实 openGemini）。
 *
 * 用法（需先启动后端，且 5432/8086 隧道已建立）：pnpm smoke
 *
 * 覆盖链路：
 *   1. 健康检查反映两个存储的真实状态
 *   2. 登录拿令牌
 *   3. 重置演示基线（保证可重复执行）
 *   4. 读接口抽样
 *   5. 写命令流水线（计划确认 / 工单派工 / 接单 / 开始 / 完成）
 *   6. 断言数据确实写进了 openGauss（绕过 HTTP 直连查库）
 *   7. 断言遥测确实写进了 openGemini
 *
 * 设计说明（避免误判）
 *   - 工单命令是**单一端点** `POST /api/work-orders/:id` + `{ action }`，
 *     不是 `/assign` `/accept` 这类子路径。
 *   - 断言用的是**增量对比**：先记下命令前的行数，再比命令后，
 *     否则种子数据里已有的审计/子表行会把断言蒙过去。
 *   - 测试对象与基线状态强耦合，基线取自真库实测（见下方常量注释）。
 */
import { closePool, queryScalar } from '../db/openGaussClient.js';
import { probe as geminiProbe, query as geminiQuery } from '../db/openGeminiClient.js';
import { env } from '../config/env.js';
import { DEMO_PASSWORD } from '../seeds/seed-data.js';

const BASE = `http://127.0.0.1:${env.PORT}`;

/**
 * 测试对象（与种子基线强绑定，改动种子时需同步）
 *   PLAN-001 : PENDING_CONFIRM → confirm 合法
 *   WO-001   : 属 PLAN-002，状态 ASSIGNED，已有 work_order_crew 行 → 走 accept/start/complete
 *   WO-002   : 属 PLAN-002，状态 READY → 走 assign
 *   PLAN-002 下的 tasks 有 2 行（TASK-001/002），task_dependencies 有 1 行
 */
const PLAN_BEING_CONFIRMED = 'PLAN-001';
const WO_FLOW = 'WO-001';
const WO_TO_ASSIGN = 'WO-002';
const EQUIPMENT_FOR_ASSIGN = 'EQ-IMG-05';
const TASKS_OWNER_PLAN = 'PLAN-002';
const TELEMETRY = 'equipment_telemetry';

let passed = 0;
let failed = 0;

function check(label: string, condition: boolean, detail = ''): void {
  if (condition) {
    passed += 1;
    console.log(`  ✅ ${label}${detail ? ` — ${detail}` : ''}`);
  } else {
    failed += 1;
    console.log(`  ❌ ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

async function call(
  method: string,
  path: string,
  options: { token?: string; body?: unknown } = {},
): Promise<{ status: number; json: any }> {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text.slice(0, 200) };
  }
  return { status: res.status, json };
}

/** 直连 openGauss 数一行，用于增量断言 */
async function count(sql: string, params: unknown[] = []): Promise<number> {
  const v = await queryScalar<number | string>(sql, params);
  return Number(v ?? 0);
}

async function main(): Promise<void> {
  console.log('PSMS 端到端冒烟测试');
  console.log(`  后端       : ${BASE}`);
  console.log(`  openGauss  : ${env.OPENGAUSS_DSN}`);
  console.log(`  openGemini : ${env.OPENGEMINI_URL} / ${env.OPENGEMINI_DATABASE}`);
  console.log();

  // ---------- 1. 健康检查 ----------
  console.log('[1] 健康检查');
  const health = await call('GET', '/api/health');
  check('GET /api/health 返回 200', health.status === 200, `status=${health.status}`);
  check('openGauss 已连接', health.json?.openGauss?.connected === true);
  check(
    'openGauss 版本可读',
    typeof health.json?.openGauss?.version === 'string' && health.json.openGauss.version.length > 0,
    health.json?.openGauss?.version,
  );
  check(
    '健康检查能读到业务表行数',
    typeof health.json?.openGauss?.tables?.plans === 'number',
    `plans=${health.json?.openGauss?.tables?.plans}`,
  );
  const gemini = await geminiProbe();
  check('openGemini 可达', gemini.reachable, `${gemini.latencyMs}ms`);
  check('openGemini 目标库存在', gemini.databaseExists, gemini.database);

  // ---------- 2. 登录 ----------
  console.log();
  console.log('[2] 登录');
  const login = await call('POST', '/api/auth/login', {
    body: { username: 'admin', password: DEMO_PASSWORD },
  });
  // 登录接口返回 { ok, data: { accessToken, refreshToken, user } }
  const token: string | undefined = login.json?.data?.accessToken ?? login.json?.data?.token;
  check('登录成功并返回令牌', login.status === 200 && !!token, `status=${login.status}`);
  if (!token) {
    console.log(`  响应体: ${JSON.stringify(login.json).slice(0, 300)}`);
    console.log('\n未取得令牌，终止后续测试');
    await closePool().catch(() => undefined);
    process.exit(1);
  }
  check('登录返回操作者标识', !!login.json?.data?.user?.actorId, login.json?.data?.user?.actorId);
  check(
    '登录返回数据域',
    Array.isArray(login.json?.data?.user?.dataScope),
    JSON.stringify(login.json?.data?.user?.dataScope),
  );

  // ---------- 3. 重置到固定基线（保证可重复执行） ----------
  // 注意：/api/demo/reset 需要鉴权，必须在登录之后调用
  console.log();
  console.log('[3] 重置演示基线（保证冒烟可重复执行）');
  const reset = await call('POST', '/api/demo/reset', { token, body: {} });
  check('POST /api/demo/reset 成功', reset.status === 200, `status=${reset.status}`);

  const planStatusBefore = await queryScalar<string>('SELECT status FROM plans WHERE id = $1', [
    PLAN_BEING_CONFIRMED,
  ]);
  check(
    `重置后 ${PLAN_BEING_CONFIRMED} 回到 PENDING_CONFIRM`,
    planStatusBefore === 'PENDING_CONFIRM',
    `status=${planStatusBefore}`,
  );
  const woStatusBefore = await queryScalar<string>('SELECT status FROM work_orders WHERE id = $1', [
    WO_FLOW,
  ]);
  check(`重置后 ${WO_FLOW} 回到 ASSIGNED`, woStatusBefore === 'ASSIGNED', `status=${woStatusBefore}`);

  // ---------- 4. 读接口 ----------
  console.log();
  console.log('[4] 读接口抽样');
  const reads: Array<[string, string]> = [
    ['计划台账', '/api/plans?page=1&pageSize=5'],
    ['计划详情', `/api/plans/${PLAN_BEING_CONFIRMED}`],
    ['计划任务列表', `/api/plans/${TASKS_OWNER_PLAN}/tasks`],
    ['工单列表', '/api/work-orders?page=1&pageSize=5'],
    ['工单详情', `/api/work-orders/${WO_FLOW}`],
    ['异常列表', '/api/exceptions?page=1&pageSize=5'],
    ['联锁列表', '/api/interlocks?page=1&pageSize=5'],
    ['预约列表', '/api/appointments?page=1&pageSize=5'],
    ['离线包列表', '/api/offline/packets?page=1&pageSize=5'],
    ['报表', '/api/reports/'],
    ['审计日志', '/api/audit-logs?page=1&pageSize=5'],
    ['系统设置', '/api/settings'],
    ['运行监控', '/api/monitor/operations'],
  ];
  for (const [label, path] of reads) {
    const r = await call('GET', path, { token });
    check(`GET ${path}`, r.status === 200, `${label} status=${r.status}`);
  }

  // ---------- 5. 写命令流水线 ----------
  console.log();
  console.log('[5] 写命令流水线');

  // 记下命令前的审计与子表行数，用于增量断言
  const auditBefore = {
    plan: await count(`SELECT COUNT(*) FROM audit_logs WHERE object_id = $1`, [PLAN_BEING_CONFIRMED]),
    wo: await count(`SELECT COUNT(*) FROM audit_logs WHERE object_id = $1`, [WO_FLOW]),
  };

  // 5.1 计划确认（PENDING_CONFIRM → CONFIRMED）
  const confirm = await call('POST', `/api/plans/${PLAN_BEING_CONFIRMED}/confirm`, {
    token,
    body: { supplements: { trackNo: 'G1' } },
  });
  check('确认计划', confirm.status === 200, `status=${confirm.status}`);

  // 5.2 派工（WO-002: READY → ASSIGNED）
  const assign = await call('POST', `/api/work-orders/${WO_TO_ASSIGN}`, {
    token,
    body: { action: 'assign', equipmentId: EQUIPMENT_FOR_ASSIGN, assignedOperator: 'ACTOR-OPERATOR' },
  });
  check('派工', assign.status === 200, `status=${assign.status}`);

  // 5.3 接单（WO-001: ASSIGNED → ACCEPTED）
  const accept = await call('POST', `/api/work-orders/${WO_FLOW}`, {
    token,
    body: { action: 'accept' },
  });
  check('接单', accept.status === 200, `status=${accept.status}`);

  // 5.4 开始作业（ACCEPTED → IN_PROGRESS）
  const start = await call('POST', `/api/work-orders/${WO_FLOW}`, {
    token,
    body: { action: 'start' },
  });
  check('开始作业', start.status === 200, `status=${start.status}`);

  // 5.5 完成作业（IN_PROGRESS → COMPLETED，带执行反馈）
  const complete = await call('POST', `/api/work-orders/${WO_FLOW}`, {
    token,
    body: {
      action: 'complete',
      feedback: { quality: 'GOOD', comment: '冒烟测试完成', reportedBy: 'ACTOR-OPERATOR' },
    },
  });
  check('完成作业', complete.status === 200, `status=${complete.status}`);

  // ---------- 6. 断言真的落到了 openGauss ----------
  console.log();
  console.log('[6] 关系库落库校验（绕过 HTTP，直连 openGauss）');

  const planStatus = await queryScalar<string>('SELECT status FROM plans WHERE id = $1', [
    PLAN_BEING_CONFIRMED,
  ]);
  check(
    `plans 表 ${PLAN_BEING_CONFIRMED} 状态已变更为 CONFIRMED`,
    planStatus === 'CONFIRMED',
    `status=${planStatus}`,
  );
  const confirmedBy = await queryScalar<string>('SELECT confirmed_by FROM plans WHERE id = $1', [
    PLAN_BEING_CONFIRMED,
  ]);
  check('计划确认人已落库', confirmedBy === 'ACTOR-ADMIN', `confirmed_by=${confirmedBy}`);

  const woStatus = await queryScalar<string>('SELECT status FROM work_orders WHERE id = $1', [WO_FLOW]);
  check(`work_orders 表 ${WO_FLOW} 状态为 COMPLETED`, woStatus === 'COMPLETED', `status=${woStatus}`);

  const feedback = await queryScalar<string>(
    'SELECT feedback_quality FROM work_orders WHERE id = $1',
    [WO_FLOW],
  );
  check('执行反馈已落到列（嵌套对象已摊平）', feedback === 'GOOD', `feedback_quality=${feedback}`);

  const woAssigned = await queryScalar<string>('SELECT status FROM work_orders WHERE id = $1', [
    WO_TO_ASSIGN,
  ]);
  check(`派工后 ${WO_TO_ASSIGN} 状态为 ASSIGNED`, woAssigned === 'ASSIGNED', `status=${woAssigned}`);

  const crewCount = await count('SELECT COUNT(*) FROM work_order_crew WHERE work_order_id = $1', [
    WO_FLOW,
  ]);
  check('子表 work_order_crew 有数据（内嵌数组已规范化）', crewCount > 0, `${crewCount} 行`);

  const depCount = await count('SELECT COUNT(*) FROM task_dependencies');
  check('子表 task_dependencies 有数据（依赖图已落表）', depCount > 0, `${depCount} 行`);

  const signalCount = await count('SELECT COUNT(*) FROM interlock_input_signals');
  check('子表 interlock_input_signals 有数据', signalCount > 0, `${signalCount} 行`);

  const taskCount = await count('SELECT COUNT(*) FROM tasks WHERE plan_id = $1', [TASKS_OWNER_PLAN]);
  check(`tasks 表有 ${TASKS_OWNER_PLAN} 的拆解任务`, taskCount > 0, `${taskCount} 行`);

  // 审计用增量断言：种子本身有审计行，只比总量会被蒙过去
  const auditAfterPlan = await count('SELECT COUNT(*) FROM audit_logs WHERE object_id = $1', [
    PLAN_BEING_CONFIRMED,
  ]);
  check(
    `审计表新增 ${PLAN_BEING_CONFIRMED} 的留痕`,
    auditAfterPlan > auditBefore.plan,
    `${auditBefore.plan} → ${auditAfterPlan} 行`,
  );
  const auditAfterWo = await count('SELECT COUNT(*) FROM audit_logs WHERE object_id = $1', [WO_FLOW]);
  check(
    `审计表新增 ${WO_FLOW} 的命令留痕（≥3 条）`,
    auditAfterWo - auditBefore.wo >= 3,
    `${auditBefore.wo} → ${auditAfterWo} 行`,
  );

  const actionRows = await count(
    `SELECT COUNT(*) FROM audit_logs WHERE object_id = $1 AND action = 'plan:confirm'`,
    [PLAN_BEING_CONFIRMED],
  );
  check('存在 plan:confirm 动作留痕', actionRows > 0, `${actionRows} 行`);

  // ---------- 7. 断言遥测写进了 openGemini ----------
  console.log();
  console.log('[7] 时序库校验（openGemini）');
  if (gemini.reachable) {
    const measurements = await geminiQuery('SHOW MEASUREMENTS', env.OPENGEMINI_DATABASE);
    const names = (measurements.series[0]?.values ?? []).map((row) => String(row[0]));
    check('存在遥测 measurement', names.includes(TELEMETRY), names.join(', ') || '(空)');

    const cnt = await geminiQuery(`SELECT COUNT(*) FROM "${TELEMETRY}"`, env.OPENGEMINI_DATABASE);
    const points = Number(cnt.series[0]?.values?.[0]?.[1] ?? 0);
    check('遥测点数 > 0', points > 0, `${points} 点`);

    const latest = await geminiQuery(
      `SELECT * FROM "${TELEMETRY}" ORDER BY time DESC LIMIT 1`,
      env.OPENGEMINI_DATABASE,
    );
    const series = latest.series[0];
    check('可读回最新遥测点', !!series && (series.values?.length ?? 0) > 0);
    if (series?.columns) {
      console.log(`     列: ${series.columns.join(', ')}`);
      console.log(`     值: ${JSON.stringify(series.values?.[0])}`);
    }
  } else {
    console.log(`  ⚠️ openGemini 不可达，跳过时序校验：${gemini.error}`);
  }

  // ---------- 汇总 ----------
  await closePool();
  console.log();
  console.log('='.repeat(62));
  console.log(`结果：通过 ${passed} 项，失败 ${failed} 项`);
  console.log('='.repeat(62));
  process.exit(failed === 0 ? 0 : 1);
}

main().catch(async (err) => {
  console.error('冒烟测试异常:', err);
  await closePool().catch(() => undefined);
  process.exit(1);
});
