import { Alert, Button, Card, Modal, Select, Space, Table, Typography } from 'antd';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Plan } from '../../../contracts';
import { loadCurrentDemoSession } from '../../../auth';
import { useDemoRuntime, useDemoSelector } from '../../../runtime';
import { liveApi, loadLivePlans } from '../../../real/client';
import { csvTemplate, parsePlanCsv } from '../csvImport';

export default function LiveCsvImportPanel() {
  const runtime = useDemoRuntime(); const navigate = useNavigate();
  const state = useDemoSelector(current => current);
  const session = loadCurrentDemoSession();
  const areas = ['AREA-A', 'AREA-B', 'AREA-C'].filter(area => session.dataScope.some(scope => [area, '*', 'GLOBAL'].includes(scope)));
  const [workArea, setWorkArea] = useState(areas[0]);
  const [pending, setPending] = useState<Plan[]>([]); const [error, setError] = useState('');
  const [feedback, setFeedback] = useState(''); const [loading, setLoading] = useState(false);
  const permitted = !session.readOnly && !!workArea && (session.fullAdmin || session.permissions?.includes('pps:api:plans:write'));
  const commit = async () => {
    setLoading(true); let savedCount = 0;
    try {
      if (!permitted || !areas.includes(workArea)) throw new Error('当前账号无此作业区的导入权限');
      const date = pending[0].arrivalDepartureTime.slice(0, 10);
      const result = await liveApi('/plans/import', { method: 'POST', body: JSON.stringify({ plans: pending.map(plan => ({
        planBatchNo: plan.planBatchNo, trainNo: plan.trainNo, cargoType: plan.cargoType, trackNo: plan.trackNo, workArea,
        arrivalTime: plan.arrivalDepartureTime.split('/')[0], departureTime: plan.arrivalDepartureTime.split('/')[1],
      })) }) });
      savedCount = result.data.items.length; setPending([]);
      const plans = await loadLivePlans(); runtime.store.replaceDomainState(candidate => { candidate.plan.plans = plans; });
      setFeedback(`已存入业务数据库 ${savedCount} 条计划。`); setError('');
      void navigate(`/dispatch/plans?date=${date}&workArea=${encodeURIComponent(workArea)}`);
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '导入失败';
      setError(savedCount ? `数据库已保存 ${savedCount} 条计划，台账刷新失败：${message}。请刷新页面，勿重复导入。` : message);
    } finally { setLoading(false); }
  };
  return <Card size="small" title="导入到发计划" style={{ marginBlock: 16 }}>
    <Space wrap>
      <Select aria-label="导入作业区" value={workArea} disabled={loading} options={areas.map(area => ({ value: area, label: area }))}
        onChange={value => { setWorkArea(value); setPending([]); }} />
      <Button onClick={() => {
        const url = URL.createObjectURL(new Blob([csvTemplate], { type: 'text/csv;charset=utf-8' }));
        const link = document.createElement('a'); link.href = url; link.download = 'PSMS-到发计划模板.csv'; link.click(); URL.revokeObjectURL(url);
      }}>下载 CSV 模板</Button>
      <label>选择 CSV 文件 <input aria-label="选择 CSV 文件" type="file" accept=".csv,text/csv" disabled={!permitted || loading}
        onChange={async event => {
          const file = event.target.files?.[0]; event.target.value = ''; if (!file) return;
          setError(''); setFeedback(''); setPending([]);
          try { setPending(parsePlanCsv(await file.text(), state.plan.plans)); }
          catch (cause) { setError(cause instanceof Error ? cause.message : 'CSV 读取失败'); }
        }} /></label>
    </Space>
    <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>使用当前平台账号导入所选作业区，服务端检查权限与重复批次。</Typography.Paragraph>
    {error ? <Alert type="error" title={error} /> : null}
    {feedback ? <Alert type="success" title={feedback} /> : null}
    <Modal title={`导入预览 · ${workArea} · ${pending.length} 条计划`} open={pending.length > 0} width={850}
      confirmLoading={loading} onCancel={() => { if (!loading) setPending([]); }} onOk={() => void commit()}
      okButtonProps={{ disabled: !permitted }} okText="确认导入" cancelText="取消">
      <Table rowKey="id" dataSource={pending} pagination={{ pageSize: 5 }} columns={[
        { title: '计划批次', dataIndex: 'planBatchNo' }, { title: '车次', dataIndex: 'trainNo' },
        { title: '股道', dataIndex: 'trackNo' }, { title: '货类', dataIndex: 'cargoType' }, { title: '到发时间', dataIndex: 'arrivalDepartureTime' },
      ]} />
      {error ? <Alert type="error" title={error} /> : null}
    </Modal>
  </Card>;
}
