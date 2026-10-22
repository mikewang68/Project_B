import { Alert, Button, Card, Modal, Select, Space, Table, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDemoRuntime, useDemoSelector } from '../../../runtime';
import type { Plan } from '../../../contracts';
import { csvTemplate, parsePlanCsv } from '../csvImport';
import { getImportedPlans, saveImportedPlans } from '../importedPlans';
import { BackendConnectionFields, useBackendConnection } from '../../backend-connection/BackendConnection';

export default function CsvImportPanel() {
  const runtime = useDemoRuntime();
  const state = useDemoSelector((current) => current);
  const navigate = useNavigate();
  const [pending, setPending] = useState<Plan[]>([]);
  const [error, setError] = useState('');
  const [feedback, setFeedback] = useState('');
  const [target, setTarget] = useState<'browser' | 'database'>('browser');
  const [loading, setLoading] = useState(false);
  const connection = useBackendConnection();
  const permitted = ['DISPATCHER', 'INTERFACE_OPS'].includes(state.session.roleCode) && state.session.dataScope.some((area) => ['AREA-A', '*', 'GLOBAL'].includes(area));
  const validate = (plans: readonly Plan[]) => {
    const batches = new Set(runtime.store.getState().plan.plans.map((plan) => plan.planBatchNo));
    for (const plan of plans) {
      if (batches.has(plan.planBatchNo)) throw new Error(`计划批次已存在：${plan.planBatchNo}`);
      batches.add(plan.planBatchNo);
      if (target === 'database') continue;
      const track = runtime.store.getState().resource.tracks.find((item) => item.trackNo === plan.trackNo);
      if (!track) throw new Error(`股道不存在：${plan.trackNo}`);
      if (!track.compatibleCargoTypes.includes(plan.cargoType)) throw new Error(`${plan.trackNo} 不兼容货类 ${plan.cargoType}`);
    }
  };
  const commit = async () => {
    setLoading(true);
    let databaseCount = 0;
    try {
      if (!permitted) throw new Error('当前角色无导入权限');
      validate(pending);
      let imported = pending;
      if (target === 'database') {
        if (!connection.token) throw new Error('请先登录业务服务');
        const response = await fetch('/api/plans/import', { method: 'POST', headers: { 'content-type': 'application/json', authorization: `Bearer ${connection.token}` }, signal: AbortSignal.timeout(30_000),
          body: JSON.stringify({ plans: pending.map((plan) => ({ planBatchNo: plan.planBatchNo, trainNo: plan.trainNo, cargoType: plan.cargoType, trackNo: plan.trackNo,
            arrivalTime: plan.arrivalDepartureTime.split('/')[0], departureTime: plan.arrivalDepartureTime.split('/')[1], workArea: 'AREA-A' })) }) });
        const result = await response.json();
        if (!response.ok || !Array.isArray(result.data?.items)) throw new Error(result.message ?? '数据库导入失败');
        databaseCount = result.data.items.length;
        imported = pending.map((plan) => ({ ...plan, id: result.data.items.find((item: { planBatchNo: string }) => item.planBatchNo === plan.planBatchNo)._id }));
      }
      const previous = getImportedPlans();
      saveImportedPlans([...previous, ...imported]);
      try {
        runtime.store.replaceDomainState((candidate) => {
          candidate.plan.plans.push(...structuredClone(imported));
          candidate.configAudit.audit.push({ id: `AUD-CSV-${crypto.randomUUID()}`, actorId: candidate.session.actorId, operatorTerminal: 'WEB',
            action: 'plan:import-csv', objectType: 'DO-001', objectId: pending.map((plan) => plan.id).join(','), before: {},
            after: { count: pending.length, batches: pending.map((plan) => plan.planBatchNo) }, reason: '手工导入 CSV', traceId: `TRACE-CSV-${crypto.randomUUID()}`, occurredAt: new Date().toISOString() });
        });
      } catch (cause) { saveImportedPlans(previous); throw cause; }
      setFeedback(`已${target === 'database' ? '存入数据库并导入演示界面' : '导入'} ${pending.length} 条计划，请确认计划后进入生产准备建议。`);
      const date = pending[0].arrivalDepartureTime.slice(0, 10);
      setPending([]); setError('');
      void navigate(`/dispatch/plans?date=${date}&workArea=AREA-A`);
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '导入失败';
      if (databaseCount) {
        setPending([]);
        setError(`数据库已保存 ${databaseCount} 条计划，但同步到本地演示失败：${message}。请勿重复提交。`);
      } else setError(message);
    }
    finally { setLoading(false); }
  };
  return <Card size="small" title="手工导入到发计划" style={{ marginBlock: 16 }}>
    <Space wrap>
      <Select aria-label="导入目标" value={target} onChange={(value) => { setTarget(value); setPending([]); setError(''); }} options={[{ value: 'browser', label: '导入当前演示' }, { value: 'database', label: '存入业务数据库' }]} />
      <Button onClick={() => {
        const url = URL.createObjectURL(new Blob([csvTemplate], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a'); link.href = url; link.download = 'PSMS-到发计划模板.csv'; link.click(); URL.revokeObjectURL(url);
      }}>下载 CSV 模板</Button>
      <label>选择 CSV 文件 <input aria-label="选择 CSV 文件" type="file" accept=".csv,text/csv" disabled={!permitted}
        onChange={async (event) => {
          const file = event.target.files?.[0]; event.target.value = ''; if (!file) return;
          setError(''); setFeedback(''); setPending([]);
          try {
            if (file.size > 2 * 1024 * 1024) throw new Error('CSV 文件不能超过 2 MB');
            const plans = parsePlanCsv(await file.text(), runtime.store.getState().plan.plans); validate(plans); setPending(plans);
          } catch (cause) { setError(cause instanceof Error ? cause.message : 'CSV 读取失败'); }
        }} /></label>
    </Space>
    {target === 'database' ? <div style={{ marginTop: 12, maxWidth: 400 }}><BackendConnectionFields connection={connection} /></div> : null}
    <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>UTF-8 CSV，支持中文表头；先校验预览，再整批导入。当前演示导入保存于本浏览器，可刷新后继续使用。</Typography.Paragraph>
    {error ? <Alert type="error" showIcon title={error} style={{ marginTop: 12 }} /> : null}
    {feedback ? <Alert type="success" showIcon title={feedback} style={{ marginTop: 12 }} /> : null}
    <Modal title={`导入预览 · ${pending.length} 条计划`} open={pending.length > 0} width={850} confirmLoading={loading} onCancel={() => { if (!loading) { setPending([]); setError(''); } }} onOk={() => void commit()} okButtonProps={{ disabled: target === 'database' && !connection.token }} okText="确认导入" cancelText="取消">
      <Table rowKey="id" dataSource={pending} pagination={{ pageSize: 5 }} scroll={{ x: 750 }} columns={[
        { title: '计划批次', dataIndex: 'planBatchNo' }, { title: '车次', dataIndex: 'trainNo' }, { title: '股道', dataIndex: 'trackNo' }, { title: '货类', dataIndex: 'cargoType' }, { title: '到发时间', dataIndex: 'arrivalDepartureTime' },
      ]} />
      {error ? <Alert type="error" title={error} /> : null}
    </Modal>
  </Card>;
}
