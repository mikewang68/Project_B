import { Alert, Button, Card, Form, Input, InputNumber, Select } from 'antd';

export default function DispatchDetailsFields({ orderType }: { orderType: string }) {
  const form = Form.useFormInstance();
  const transferMode = Form.useWatch(['dispatchDetails', 'transferMode'], form);
  if (!['LOADING', 'UNLOADING'].includes(orderType)) return null;
  return <Card title="派遣工单填写" size="small" style={{ marginBottom: 16 }}>
    <Alert type="info" showIcon title={orderType === 'LOADING' ? '装车对应 WMS 出库' : '卸车对应 WMS 入库'}
      description="公司与货主使用后台联动配置。每种物料填写一行，库存单位填写 WMS 主数据原值。吨单位支持最多三位小数，捆、件等单位使用整数。" style={{ marginBottom: 16 }} />
    {[
      ['plannedWorkAt', '计划作业时间（含时区）'], ['operationLocation', '装卸点或股道'], ['channel', '现场通道'],
      ...(orderType === 'UNLOADING' ? [['expectedArrivalAt', '预计到达时间（含时区）']] : [['sourceWarehouseCode', '来源仓编码']]),
    ].map(([name, label]) => <Form.Item key={name} name={['dispatchDetails', name]} label={label}
      rules={[{ required: true, whitespace: true, max: 160 }]}><Input placeholder={name.endsWith('At') ? '2026-11-08T16:00:00+08:00' : undefined} /></Form.Item>)}
    <Form.Item name={['dispatchDetails', 'wagonNo']} label="车皮编号"><Input maxLength={160} /></Form.Item>
    <Form.Item name={['dispatchDetails', 'transferMode']} label="转运方式" rules={[{ required: true }]}>
      <Select options={[{ value: 'DIRECT', label: orderType === 'LOADING' ? '直装' : '直卸' }, { value: 'SHUTTLE', label: '车辆短驳' }]}
        onChange={value => { if (value === 'DIRECT') form.setFieldsValue({ dispatchDetails: { vehiclePlate: undefined, driverName: undefined } }); }} />
    </Form.Item>
    {transferMode === 'SHUTTLE' && <>
      <Form.Item name={['dispatchDetails', 'vehiclePlate']} label="实际车牌" rules={[{ required: true, whitespace: true, max: 160 }]}><Input /></Form.Item>
      <Form.Item name={['dispatchDetails', 'driverName']} label="司机姓名" rules={[{ required: true, whitespace: true, max: 160 }]}><Input /></Form.Item>
    </>}
    <Form.List name={['dispatchDetails', 'lines']} rules={[{ validator: async (_, lines) => {
      if (!lines?.length) throw new Error('至少填写一行物料');
      if (new Set(lines.map((line: { lineId?: string }) => line?.lineId)).size !== lines.length) throw new Error('物料行号必须唯一');
    } }]}>{(fields, { add, remove }, { errors }) => <>
      {fields.map(field => <Card key={field.key} size="small" title={'物料明细 ' + (field.name + 1)} style={{ marginBottom: 12 }}>
        {[
          ['lineId', '行号'], ['goodCode', 'WMS 物料编码'], ['unit', 'WMS 库存单位原值'],
        ].map(([name, label]) => <Form.Item key={name} name={[field.name, name]} label={label}
          rules={[{ required: true, whitespace: true, max: 160 }]}><Input /></Form.Item>)}
        <Form.Item name={[field.name, 'plannedQty']} label="计划数量" rules={[{ required: true }, { validator: async (_, value) => {
          if (value === undefined || value === null) return;
          if (value <= 0 || value > 1000000000 || !/^\d+(?:\.\d{1,3})?$/.test(String(value))) throw new Error('数量须大于 0，最多三位小数');
          const unit = form.getFieldValue(['dispatchDetails', 'lines', field.name, 'unit']);
          if (!['吨', 't', 'ton'].includes(unit) && !Number.isInteger(value)) throw new Error('捆、件等非吨库存单位只允许整数');
        } }]}><InputNumber min={0.001} max={1000000000} step={0.001} /></Form.Item>
        <Form.Item name={[field.name, 'batchCode']} label="实际批次"><Input maxLength={160} /></Form.Item>
        <Form.Item name={[field.name, 'supplierCode']} label="供应商编码"><Input maxLength={160} /></Form.Item>
        <Button onClick={() => remove(field.name)} disabled={fields.length === 1}>删除物料行</Button>
      </Card>)}
      <Form.ErrorList errors={errors} />
      <Button onClick={() => add({})} disabled={fields.length >= 100}>增加物料行</Button>
    </>}</Form.List>
  </Card>;
}
