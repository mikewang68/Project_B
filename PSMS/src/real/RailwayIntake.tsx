import { useEffect, useState } from 'react';
import { Alert, Button, Card, Modal, Select, Space, Table, Tag, Typography, Upload } from 'antd';
import { RailwayApiError, railwayApi } from './railwayClient';
import { BackendConnectionFields, useBackendConnection } from '../features/backend-connection/BackendConnection';
import { businessLabel } from '../presentation/businessCopy';

const endpoint = '/business/work-orders/railway-intake';
const request = (path = '', body?: unknown) => railwayApi(path, body, endpoint);
type IntakeMode = 'mail' | 'file';
type Attachment = { filename: string; sender?: string; subject?: string; payload?: unknown; proof?: string; error?: string };
type ImportedOrder = {
  id: string; status: string; plan_id: string; work_area: string; order_type: string; imported_at: string;
  intake: { externalOrderNo: string; channel: 'EMAIL' | 'FILE'; filename: string };
};

function downloadTemplate(direction: 'UNLOADING' | 'LOADING') {
  const loading = direction === 'LOADING';
  const ticket = {
    version: 'psms.railway-dispatch.v1', externalOrderNo: '请替换为甲方唯一工单编号', planId: '请替换为已确认的 PSMS 计划 ID',
    orderType: direction, priority: 'MEDIUM', description: '请填写实际货物和作业说明',
    dispatchDetails: {
      plannedWorkAt: '2026-11-10T14:00:00+08:00', operationLocation: '请填写装卸点或股道', channel: '请填写现场通道',
      transferMode: 'DIRECT', ...(loading ? { sourceWarehouseCode: '请填写 WMS 来源仓编码' }
        : { expectedArrivalAt: '2026-11-10T13:00:00+08:00' }),
      lines: [{ lineId: '1', goodCode: '请填写 WMS 物料编码', plannedQty: 1.025, unit: '吨', batchCode: '请填写实际批次，无指定批次时删除此字段' }],
    },
  };
  const url = URL.createObjectURL(new Blob([JSON.stringify(ticket, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = loading ? '铁路装车出库工单模板.json' : '铁路卸车入库工单模板.json';
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export default function RailwayIntake({ mode }: { mode: IntakeMode }) {
  const live = import.meta.env.VITE_PSMS_REAL_API === 'true';
  const connection = useBackendConnection();
  const [needsLogin, setNeedsLogin] = useState(false);
  const [items, setItems] = useState<ImportedOrder[]>([]);
  const [mailbox, setMailbox] = useState<boolean>();
  const [mail, setMail] = useState<Attachment[]>([]);
  const [candidate, setCandidate] = useState<Attachment>();
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    if (!live) return;
    let active = true;
    setError('');
    setMailbox(undefined);
    setItems([]);
    void request().then(({ data }) => {
      if (active) { setItems(data.items); setMailbox(data.mailboxAvailable); setNeedsLogin(false); }
    }).catch(err => {
      if (active) {
        setError(err instanceof Error ? err.message : '读取已导入工单失败');
        setNeedsLogin(err instanceof RailwayApiError && err.status === 401);
      }
    });
    return () => { active = false; };
  }, [live, refresh, connection.token]);

  async function run(action: () => Promise<void>) {
    setError('');
    setBusy(true);
    try { await action(); }
    catch (err) { setError(err instanceof Error ? err.message : '工单导入操作失败'); }
    finally { setBusy(false); }
  }

  return <Card style={{ margin: '16px 0' }} title={mode === 'mail' ? '邮件接收工单' : '上传工单文件'}>
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text>使用按 WMS 派遣字段填写的 UTF-8 JSON。装车填来源仓，卸车填预计到达时间，短驳填车牌和司机。确认后进入派工看板，按正常作业流程办理。</Typography.Text>
      <Space wrap>
        <Button onClick={() => downloadTemplate('UNLOADING')}>下载卸车入库模板</Button>
        <Button onClick={() => downloadTemplate('LOADING')}>下载装车出库模板</Button>
      </Space>
      {!live && <Alert type="info" showIcon title="正式工单请在服务器版导入"
        action={<Button href="https://100.65.200.125:18443/production-dispatch/?railway=1">打开服务器运行台</Button>} />}
      {error && <Alert type="error" showIcon title={error} />}
      {needsLogin && <BackendConnectionFields connection={connection} />}
      {result && <Alert type="success" showIcon title={result} />}
      {mode === 'mail' ? <>
        <Typography.Text>邮件主题请包含 [PSMS-RAILWAY]，并附上工单 JSON 文件。</Typography.Text>
        {live && mailbox === false && <Alert type="warning" showIcon title="工单邮箱未配置或当前账号没有邮箱读取权限" />}
        <Space wrap>
          <Button type="primary" disabled={!live || mailbox !== true} loading={busy} onClick={() => void run(async () => {
            const response = await request('/mail', {});
            setMail(response.data);
            setResult(response.data.length ? '' : '最近的工单邮件中没有可读的 JSON 附件');
          })}>读取最近工单邮件</Button>
          {!!mail.length && <Select style={{ minWidth: 280 }} placeholder="选择附件核对" value={null}
            options={mail.map((attachment, index) => ({ value: index, label: [attachment.subject, attachment.filename, attachment.error].filter(Boolean).join(' · '),
              disabled: Boolean(attachment.error) }))}
            onChange={index => { if (index !== null) { setError(''); setCandidate(mail[index]); } }} />}
        </Space>
      </> : <Upload accept=".json,application/json" showUploadList={false} disabled={!live || busy || needsLogin} beforeUpload={file => {
        void run(async () => {
          if (!/\.json$/i.test(file.name) || file.size > 65536) throw new Error('请选择不超过 64 KB 的 JSON 文件');
          let payload: unknown;
          try { payload = JSON.parse((await file.text()).replace(/^\uFEFF/, '')); }
          catch { throw new Error('文件不是有效的 UTF-8 JSON'); }
          setCandidate({ filename: file.name, payload });
        });
        return false;
      }}><Button type="primary" disabled={!live || needsLogin} loading={busy}>选择文件并核对</Button></Upload>}
      {live && <Table<ImportedOrder> size="small" rowKey="id" dataSource={items.filter(item => item.intake.channel === (mode === 'mail' ? 'EMAIL' : 'FILE'))}
        scroll={{ x: 830 }} locale={{ emptyText: '此来源尚无已导入工单' }} columns={[
          { title: '甲方工单编号', dataIndex: ['intake', 'externalOrderNo'] },
          { title: 'PSMS 工单编号', dataIndex: 'id', render: (value: string) => <Typography.Text ellipsis={{ tooltip: value }} copyable style={{ maxWidth: 230 }}>{value}</Typography.Text> },
          { title: '关联计划', dataIndex: 'plan_id' }, { title: '作业区', dataIndex: 'work_area' },
          { title: '作业类型', dataIndex: 'order_type', render: (value: string) => businessLabel(value) },
          { title: '状态', dataIndex: 'status', render: (value: string) => <Tag>{businessLabel(value)}</Tag> },
          { title: '导入时间', dataIndex: 'imported_at', render: (value: string) => new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) },
        ]} />}
    </Space>
    <Modal title="核对后导入正式工单" open={!!candidate} onCancel={() => setCandidate(undefined)}
      okText="确认导入派工看板" cancelText="取消" confirmLoading={busy} closable={!busy} maskClosable={!busy}
      onOk={() => candidate && void run(async () => {
        const response = await request('', {
          ticket: candidate.payload, filename: candidate.filename, ...(candidate.proof ? { mailProof: candidate.proof } : {}),
        });
        setCandidate(undefined);
        setResult(response.data.duplicate ? '相同工单已导入，已保留原记录' : '工单已进入派工看板，状态为待派工草稿');
        setRefresh(value => value + 1);
      })}>
      {error && <Alert type="error" showIcon title={error} />}
      {candidate?.sender && <Alert type="info" showIcon title="请核对发件人和计划后再导入" description={candidate.sender} />}
      <Typography.Paragraph>{candidate?.filename}</Typography.Paragraph>
      <pre style={{ maxHeight: 420, overflow: 'auto', whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{candidate ? JSON.stringify(candidate.payload, null, 2) : ''}</pre>
    </Modal>
  </Card>;
}