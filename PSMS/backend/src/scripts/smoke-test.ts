/**
 * 端到端冒烟测试。
 *
 * 用法（需先启动后端）：pnpm smoke
 *
 * 依次验证：健康检查 → 登录鉴权 → 读接口 → 写命令 → 审计留痕，
 * 确认 HTTP 层与 MongoDB 落库链路全部真实可用。
 */

const BASE = process.env.SMOKE_BASE_URL ?? 'http://127.0.0.1:3100';

let failures = 0;

function check(label: string, condition: boolean, detail = ''): void {
  const mark = condition ? '✅' : '❌';
  if (!condition) failures += 1;
  console.log(`${mark} ${label}${detail ? ` — ${detail}` : ''}`);
}

async function api(
  path: string,
  init: RequestInit & { token?: string } = {},
): Promise<{ status: number; body: any }> {
  const { token, ...rest } = init;
  const headers: Record<string, string> = {
    'content-type': 'application/json',
    ...((rest.headers as Record<string, string>) ?? {}),
  };
  if (token) headers.authorization = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, { ...rest, headers });
  const text = await res.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  return { status: res.status, body };
}

async function main(): Promise<void> {
  console.log('========================================');
  console.log(' PSMS 后端 + MongoDB 端到端冒烟测试');
  console.log('========================================');
  console.log(` 目标: ${BASE}\n`);

  // ---------- 1. 健康检查 ----------
  const health = await api('/api/health');
  check('健康检查返回 200', health.status === 200);
  check('MongoDB 已连接', health.body?.mongodb?.connected === true);
  check(
    'mongod 版本可读',
    typeof health.body?.mongodb?.version === 'string',
    `mongod ${health.body?.mongodb?.version}`,
  );
  check(
    '集合与文档数可读',
    typeof health.body?.mongodb?.collections === 'number' &&
      typeof health.body?.mongodb?.documents === 'number',
    `${health.body?.mongodb?.collections} 个集合 / ${health.body?.mongodb?.documents} 篇文档`,
  );

  // ---------- 2. 鉴权 ----------
  const badLogin = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username: 'admin', password: 'wrong-password' }),
  });
  check('错误密码被拒绝', badLogin.status === 401, `HTTP ${badLogin.status}`);

  const login = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username: 'admin', password: 'password123' }),
  });
  check('正确凭据登录成功', login.status === 200 && login.body?.ok === true);
  const token: string | undefined = login.body?.data?.accessToken;
  check('返回访问令牌', typeof token === 'string' && token.length > 20);

  const noAuthWrite = await api('/api/plans/PLAN-001/confirm', { method: 'POST' });
  check('未带令牌的写操作被拒绝', noAuthWrite.status === 401, `HTTP ${noAuthWrite.status}`);

  if (!token) {
    console.log('\n未获取到令牌，终止后续测试');
    process.exit(1);
  }

  // ---------- 3. 重置演示数据，保证测试可重复执行 ----------
  const reset = await api('/api/demo/reset', {
    method: 'POST',
    token,
    body: JSON.stringify({ scenarioId: 'SCN-01' }),
  });
  check(
    '演示数据重置成功（真正重灌种子）',
    reset.status === 200 && typeof reset.body?.data?.counts?.plans === 'number',
    `plans=${reset.body?.data?.counts?.plans} / documents 已重建`,
  );

  // ---------- 4. 读接口 ----------
  const plans = await api('/api/plans', { token });
  check('计划列表读取成功', plans.status === 200 && Array.isArray(plans.body?.data?.items));
  check(
    '计划列表返回分页信息',
    typeof plans.body?.data?.total === 'number',
    `total=${plans.body?.data?.total}`,
  );

  const detailBefore = await api('/api/plans/PLAN-001', { token });
  const statusBefore = detailBefore.body?.data?.items?.[0]?.status;
  check('计划详情读取成功', detailBefore.status === 200, `PLAN-001 状态=${statusBefore}`);

  const workOrders = await api('/api/work-orders', { token });
  check('工单列表读取成功', workOrders.status === 200);

  const monitor = await api('/api/monitor/operations', { token });
  const telemetryCount = monitor.body?.data?.items?.[0]?.telemetry?.length ?? 0;
  check('监控快照含时序遥测数据', telemetryCount > 0, `telemetry=${telemetryCount} 条`);

  const report = await api('/api/reports', { token });
  const planGroups = report.body?.data?.items?.[0]?.plans?.length ?? 0;
  check('报表聚合结果非空（数据库侧 $group）', planGroups > 0, `${planGroups} 个状态分组`);

  // ---------- 5. 写命令 ----------
  const confirm = await api('/api/plans/PLAN-001/confirm', {
    method: 'POST',
    token,
    body: JSON.stringify({ supplements: { trackNo: 'G1' } }),
  });
  check('计划确认命令执行成功', confirm.status === 200 && confirm.body?.ok === true);

  const detailAfter = await api('/api/plans/PLAN-001', { token });
  const statusAfter = detailAfter.body?.data?.items?.[0]?.status;
  check(
    '状态已由 PENDING_CONFIRM 变为 CONFIRMED（数据真实落库）',
    statusAfter === 'CONFIRMED',
    `${statusBefore} → ${statusAfter}`,
  );

  const repeat = await api('/api/plans/PLAN-001/confirm', {
    method: 'POST',
    token,
    body: JSON.stringify({}),
  });
  check('重复确认被状态机拒绝', repeat.status === 409, `HTTP ${repeat.status}`);

  // ---------- 6. 审计留痕 ----------
  const audits = await api('/api/audit-logs?action=plan:confirm', { token });
  const auditItems = audits.body?.data?.items ?? [];
  check(
    '写命令已生成业务审计',
    auditItems.some((item: any) => item.objectId === 'PLAN-001'),
    `${auditItems.length} 条 plan:confirm 审计`,
  );

  const sessionAudit = await api('/api/audit-logs?pageSize=5', { token });
  check('审计日志可分页查询', sessionAudit.status === 200);

  console.log('\n========================================');
  console.log(failures === 0 ? '✅ 全部通过' : `❌ ${failures} 项未通过`);
  console.log('========================================');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('冒烟测试异常:', err);
  process.exit(1);
});
