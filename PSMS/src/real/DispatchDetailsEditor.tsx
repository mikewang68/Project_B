import { Alert, Button, Card, Form, Space } from 'antd';
import { useEffect, useState } from 'react';
import type { SessionContext } from '../auth';
import { liveApi } from './client';
import { canRunBusinessAction, type BusinessRow } from './business';
import DispatchDetailsFields from './DispatchDetailsFields';

export default function DispatchDetailsEditor({ row, session, onSaved }: {
  row: BusinessRow; session: SessionContext; onSaved: (item: Partial<BusinessRow>) => void;
}) {
  const [form] = Form.useForm(), [busy, setBusy] = useState(false), [error, setError] = useState('');
  const path = '/business/work-orders/' + encodeURIComponent(row.id);
  const canEdit = canRunBusinessAction(session, { code: 'write', label: '填写派遣工单', permission: 'pps:api:work-orders:write' });
  const canExport = canRunBusinessAction(session, { code: 'export', label: '导出派遣资料', permission: 'pps:export' });
  useEffect(() => {
    form.resetFields();
    form.setFieldsValue({ dispatchDetails: row.dispatch_details ?? { transferMode: 'DIRECT', lines: [{ lineId: '1' }] } });
  }, [form, row.id, row.dispatch_details]);
  return <Card title="WMS 派遣资料" style={{ marginTop: 20 }}>
    {error && <Alert type="error" title={error} style={{ marginBottom: 12 }} />}
    {row.order_type === 'LOADING' && <Alert type="info" title="装车工单填写来源仓，沿用实际批次与 WMS 库存单位"
      description="可导出派遣资料与 WMS 核对，自动接单及实发回传的接口待双方接通。" style={{ marginBottom: 12 }} />}
    <Form form={form} layout="vertical" disabled={busy || !canEdit || ['COMPLETED', 'CANCELLED'].includes(row.status)} onFinish={async values => {
      setBusy(true); setError('');
      try {
        const details = values.dispatchDetails;
        const clean = Object.fromEntries(Object.entries(details).filter(([, value]) => value !== undefined && value !== ''));
        clean.lines = details.lines.map((line: Record<string, unknown>) => Object.fromEntries(Object.entries(line).filter(([, value]) => value !== undefined && value !== '')));
        const result = await liveApi(path + '/dispatch-details', { method: 'POST', body: JSON.stringify({ dispatchDetails: clean, expectedVersion: row.record_version }) });
        onSaved(result.data);
      } catch (e) { setError(e instanceof Error ? e.message : '保存未取得回执，请刷新核对'); }
      finally { setBusy(false); }
    }}>
      <DispatchDetailsFields orderType={String(row.order_type)} />
      <Button type="primary" htmlType="submit" loading={busy}>保存派遣工单资料</Button>
    </Form>
    <Space style={{ marginTop: 12 }}>
      <Button disabled={busy || !row.dispatch_details || !canExport} onClick={async () => {
        setBusy(true); setError('');
        try {
          const result = await liveApi(path + '/dispatch-export');
          const url = URL.createObjectURL(new Blob([JSON.stringify(result.data, null, 2)], { type: 'application/json' }));
          const anchor = document.createElement('a'); anchor.href = url; anchor.download = row.id + '-WMS派遣资料.json'; anchor.click();
          URL.revokeObjectURL(url);
        } catch (e) { setError(e instanceof Error ? e.message : '工单导出失败'); }
        finally { setBusy(false); }
      }}>导出 WMS 派遣资料</Button>
    </Space>
  </Card>;
}
