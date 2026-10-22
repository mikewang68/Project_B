// 仅检查 AI 调用，不连接业务数据库，不执行迁移或种子灌入。
import { env } from '../config/env.js';
import { AiServiceError, requestAi, resolveAiConfig } from '../services/ai.service.js';

const config = resolveAiConfig(env);
try {
  const answer = await requestAi(config, {
    question: '请用三句话给出当前计划的准备建议、主要风险和下一步。',
    context: {
      dataSource: 'MOCK', snapshotAt: '2026-07-19T08:00:00+08:00',
      plan: { planBatchNo: 'AI-CHECK-001', trainNo: 'T88001', cargoType: 'FLY_ASH', status: 'CONFIRMED', trackNo: 'T1', arrivalDepartureTime: '2026-07-19T09:00:00+08:00/2026-07-19T11:00:00+08:00' },
      workOrders: [], resources: [{ type: 'EQUIPMENT', status: 'AVAILABLE' }], missingFields: [], conflicts: 0,
    },
  });
  console.log(JSON.stringify({ connected: true, model: config.model, answer }, null, 2));
} catch (cause) {
  console.error(JSON.stringify({ connected: false, model: config.model, message: cause instanceof AiServiceError ? cause.message : 'AI 检查失败' }));
  process.exitCode = 1;
}
