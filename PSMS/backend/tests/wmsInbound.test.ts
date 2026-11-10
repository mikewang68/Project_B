import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { PGlite } from '@electric-sql/pglite';
import type { PoolClient } from 'pg';
import { SCHEMA_STATEMENTS } from '../src/db/schema.js';
import { WmsInbound, assertWmsGate } from '../src/services/wms-inbound.service.js';
import { executeBusinessCommand, createBusinessWorkOrder } from '../src/services/business-command.service.js';
import { saveDispatchDetails, exportDispatchDetails } from '../src/services/dispatch-details.service.js';
import { inboundLine, workOrderDispatchDetails, validateDispatchDirection } from '../src/services/wms-contract.js';
import { mayReadBusinessRoute, mayWriteBusinessRoute } from '../src/middleware/sso-policy.js';

describe('PSMS WMS durable inbound workflow', () => {
  let db: PGlite, service: WmsInbound, connect: () => Promise<PoolClient>;
  const http = vi.fn<typeof fetch>();
  const actor = { actorId: 'joint-user', subject: 'joint-iam-user', roleCode: 'dispatcher', dataScope: ['AREA-A'] };
  const input = { expectedVersion: '', companyCode: 'TEST-C', ownerCode: 'TEST-O', expectedArrivalAt: '2026-11-04T16:00:00+08:00',
    lines: [{ lineId: '1', goodCode: 'TEST-STEEL', plannedQty: 100, unit: 'BUNDLE' }] };
  let task: { task_id: string; payload: Record<string, unknown> };
  const warehouse = (sequence = 1) => ({ schemaVersion: '1.0', eventId: 'warehouse-' + sequence,
    taskId: task.task_id, version: 1, sequence, eventType: 'WAREHOUSE_CONFIRMED',
    occurredAt: '2026-11-04T08:01:00Z', warehouseCode: 'TEST-W', wmsOrderCode: 'WMS-001' });
  const receipt = (sequence: number, full: boolean) => ({ ...warehouse(sequence), eventId: 'receipt-' + sequence,
    eventType: full ? 'FULLY_RECEIVED' : 'PARTIALLY_RECEIVED', receiptOperationCode: 'RECEIVE-' + sequence,
    lines: [{ lineId: '1', goodCode: 'TEST-STEEL', unit: 'BUNDLE', receivedDelta: full ? 40 : 60,
      receivedQty: full ? 100 : 60, remainingQty: full ? 0 : 40 }] });
  beforeAll(async () => {
    vi.stubEnv('PSMS_WMS_ENABLED', 'true');
    db = await PGlite.create();
    for (const sql of SCHEMA_STATEMENTS) {
      const add = sql.match(/^ALTER TABLE (\w+) ADD COLUMN (\w+)/);
      if (add && (await db.query('SELECT 1 FROM information_schema.columns WHERE table_schema=$1 AND table_name=$2 AND column_name=$3', ['public', add[1], add[2]])).rows.length) continue;
      await db.exec(sql);
    }
    connect = async () => ({ query: async (sql: string, values?: unknown[]) => {
      if (sql === 'SELECT current_database() AS name') return { rows: [{ name: 'psms_wms_joint_test' }] };
      if (sql.includes('CREATE SCHEMA')) { await db.exec(sql); return { rows: [] }; }
      return db.query(sql, values);
    }, release: () => {} }) as unknown as PoolClient;
    service = new WmsInbound({ database: 'psms_wms_joint_test', baseUrl: 'http://127.0.0.1:21984',
      companyCode: 'TEST-C', ownerCode: 'TEST-O', sendToken: 's'.repeat(32), receiveToken: 'r'.repeat(32) }, connect, http);
    await service.initialize();
    await db.query(`INSERT INTO plans(id,plan_batch_no,train_no,work_area,status,cargo_type,source_station,arrive_time)
      VALUES('P1','B1','TEST-01','AREA-A','CONFIRMED','STEEL','联调测试站',CURRENT_TIMESTAMP)`);
    await db.query(`INSERT INTO work_orders(id,plan_id,plan_batch_no,work_area,order_type,status)
      VALUES('WO1','P1','B1','AREA-A','UNLOADING','IN_PROGRESS')`);
    input.expectedVersion = (await db.query<{ version: string }>('SELECT updated_at::text AS version FROM work_orders WHERE id=$1', ['WO1'])).rows[0].version;
  }, 30000);
  afterAll(async () => { vi.unstubAllEnvs(); await db?.close(); });

  it('records one native draft when the same WMS order is submitted by file and email', async () => {
    const dispatchDetails = {
      plannedWorkAt: '2026-11-09T15:00:00+08:00', expectedArrivalAt: '2026-11-09T14:00:00+08:00',
      operationLocation: 'T4', channel: '入库通道-4', transferMode: 'SHUTTLE',
      vehiclePlate: 'TEST-08', driverName: '联调司机',
      lines: [{ lineId: '1', goodCode: 'TEST-STEEL', plannedQty: 1.025, unit: '吨', batchCode: 'TEST-BATCH' }],
    };
    const command = { planId: 'P1', orderType: 'UNLOADING', priority: 'HIGH', description: '甲方钢材到货', dispatchDetails };
    const source = { externalOrderNo: 'CUSTOMER-RAIL-01', filename: 'customer-rail.json', channel: 'FILE' as const };
    const first = await createBusinessWorkOrder(command, actor, connect, source);
    const duplicate = await createBusinessWorkOrder(command, actor, connect, { ...source, channel: 'EMAIL' });
    expect(duplicate).toMatchObject({ duplicate: true, item: { id: first.item.id, status: 'DRAFT' } });
    await expect(createBusinessWorkOrder({ ...command, description: '不同内容' }, actor, connect, source))
      .rejects.toMatchObject({ statusCode: 409, errorCode: 'RAILWAY_IMPORT_CONFLICT' });
    const original = (await db.query('SELECT dispatch_details FROM work_orders WHERE id=$1', [first.item.id])).rows[0];
    expect(original).toEqual({ dispatch_details: dispatchDetails });
    const audit = await db.query("SELECT after_state FROM audit_logs WHERE object_id=$1 AND action='work-orders:railway-import'", [first.item.id]);
    expect(audit.rows).toEqual([expect.objectContaining({ after_state: expect.objectContaining({ intake: source }) })]);
  });

  it('limits integration to the explicitly selected isolated database', async () => {
    await expect(new WmsInbound({ ...service.config, database: 'psms' }, connect).initialize()).rejects.toThrow('隔离');
  });
  it('checks company, local role, area and version before recording any outgoing task', async () => {
    await expect(service.dispatch('WO1', { ...input, companyCode: 'OTHER' }, actor)).rejects.toMatchObject({ statusCode: 403 });
    await expect(service.dispatch('WO1', input, { ...actor, roleCode: 'viewer' })).rejects.toMatchObject({ statusCode: 403 });
    await expect(service.dispatch('WO1', input, { ...actor, dataScope: ['AREA-B'] })).rejects.toMatchObject({ statusCode: 404 });
    await expect(service.dispatch('WO1', { ...input, expectedVersion: 'stale' }, actor)).rejects.toMatchObject({ statusCode: 409 });
    expect((await db.query('SELECT * FROM psms_wms.tasks')).rows).toHaveLength(0);
  });
  it('persists a stable task, original outgoing event, four prerequisite tasks and audit atomically', async () => {
    task = await service.dispatch('WO1', input, actor);
    expect((await service.dispatch('WO1', input, actor)).task_id).toBe(task.task_id);
    expect((await db.query('SELECT * FROM psms_wms.tasks')).rows).toHaveLength(1);
    expect((await db.query('SELECT * FROM psms_wms.outbox')).rows).toHaveLength(1);
    expect((await db.query('SELECT * FROM tasks WHERE work_order_id=$1', ['WO1'])).rows).toHaveLength(4);
    expect((await db.query("SELECT actor_id FROM audit_logs WHERE action='wms:dispatch' AND object_id='WO1'")).rows).toEqual([{ actor_id: actor.subject }]);
    await expect(service.dispatch('WO1', { ...input, lines: [{ ...input.lines[0], plannedQty: 101 }] }, actor)).rejects.toThrow('原内容');
    expect((await service.detail('WO1', actor)).task.state).toBe('PENDING_SEND');
  });
  it('retries a lost HTTP response with the original event and never infers receipts from acceptance', async () => {
    http.mockRejectedValueOnce(new Error('lost response'));
    await service.deliver();
    expect((await service.detail('WO1', actor)).task.state).toBe('PENDING_SEND');
    await db.query('UPDATE psms_wms.outbox SET next_attempt_at=CURRENT_TIMESTAMP');
    http.mockResolvedValueOnce(Response.json({ code: 'NOT_FOUND' }, { status: 404 }));
    http.mockResolvedValueOnce(Response.json({ taskId: task.task_id, version: 1, status: 'WAITING_WAREHOUSE' }, { status: 202 }));
    await service.deliver();
    expect(http.mock.calls[0][1]?.body).toEqual(http.mock.calls[2][1]?.body);
    expect((await service.detail('WO1', actor)).task.state).toBe('WAITING_WAREHOUSE');
    await expect(assertWmsGate(await connect(), 'WO1', 'transfer')).rejects.toThrow('目的仓');
  });
  it('buffers a missing sequence and applies it only after warehouse confirmation arrives', async () => {
    const accepted = await service.receive(receipt(2, false));
    expect((await service.detail('WO1', actor)).pendingSequences).toEqual([2]);
    await service.receive(warehouse());
    const detail = await service.detail('WO1', actor);
    expect(detail.task.state).toBe('PARTIALLY_RECEIVED');
    expect(detail.task.last_sequence).toBe(2);
    expect(detail.task.progress).toEqual([{ lineId: '1', receivedQty: 60, remainingQty: 40 }]);
    expect(await service.receive(receipt(2, false))).toEqual(accepted);
    await expect(assertWmsGate(await connect(), 'WO1', 'transfer')).resolves.toBeUndefined();
    await expect(assertWmsGate(await connect(), 'WO1', 'complete')).rejects.toThrow('收齐');
    await expect(service.receive({ ...receipt(2, false), occurredAt: '2026-11-04T08:11:00Z' })).rejects.toMatchObject({ statusCode: 409 });
  });
  it('uses the remote snapshot to expose a gap without inventing a FULLY_RECEIVED event', async () => {
    http.mockResolvedValueOnce(Response.json({ taskId: task.task_id, version: 1, status: 'FULLY_RECEIVED',
      warehouseCode: 'TEST-W', wmsOrderCode: 'WMS-001', lastSequence: 3,
      lines: [{ ...input.lines[0], receivedQty: 100, remainingQty: 0 }] }));
    await service.reconcile();
    const detail = await service.detail('WO1', actor);
    expect(detail.task.remote_snapshot.lastSequence).toBe(3);
    expect(detail.task.state).toBe('PARTIALLY_RECEIVED');
    await expect(assertWmsGate(await connect(), 'WO1', 'complete')).rejects.toThrow('收齐');
  });
  it('rejects wrong warehouse, unit, inconsistent quantities and reused receipt operations', async () => {
    await expect(service.receive({ ...receipt(3, true), warehouseCode: 'OTHER' })).rejects.toThrow('仓库');
    await expect(service.receive({ ...receipt(3, true), lines: [{ ...receipt(3, true).lines[0], unit: 'TON' }] })).rejects.toThrow('单位');
    await expect(service.receive({ ...receipt(3, true), lines: [{ ...receipt(3, true).lines[0], receivedDelta: 100 }] })).rejects.toThrow('增量');
    await expect(service.receive({ ...receipt(3, true), receiptOperationCode: 'RECEIVE-2' })).rejects.toThrow('原事件');
    expect((await service.detail('WO1', actor)).task.last_sequence).toBe(2);
  });
  it('requires WMS receipt and native prerequisite tasks before completion, then permits one audited completion', async () => {
    const before = (await db.query<{ version: string }>('SELECT updated_at::text AS version FROM work_orders WHERE id=$1', ['WO1'])).rows[0];
    const command = { action: 'complete', reason: '人工复核完成', expectedVersion: before.version };
    await expect(executeBusinessCommand('work-orders', 'WO1', command, actor, connect)).rejects.toThrow('收齐');
    await service.receive(receipt(3, true));
    await expect(executeBusinessCommand('work-orders', 'WO1', command, actor, connect)).rejects.toThrow('关联任务');
    const tasks = (await db.query<{ id: string }>('SELECT id FROM tasks WHERE work_order_id=$1 ORDER BY order_no', ['WO1'])).rows;
    await expect(service.taskCommand('WO1', { taskId: tasks[2].id, action: 'start', reason: '转运' }, actor)).rejects.toThrow('此前');
    for (const step of tasks) {
      await service.taskCommand('WO1', { taskId: step.id, action: 'start', reason: '联调开始' }, actor);
      await service.taskCommand('WO1', { taskId: step.id, action: 'complete', reason: '联调确认完成' }, actor);
    }
    await executeBusinessCommand('work-orders', 'WO1', command, actor, connect);
    expect((await db.query('SELECT status FROM work_orders WHERE id=$1', ['WO1'])).rows[0]).toEqual({ status: 'COMPLETED' });
    await service.receive(receipt(3, true));
    expect((await db.query('SELECT * FROM psms_wms.events')).rows).toHaveLength(3);
  });
  it('keeps read, dispatch and execute grants separate and preserves readonly denial', () => {
    const current = { readOnly: false, superAdmin: false, permissions: ['pps:api:work-orders:read', 'pps:api:work-orders:write'] };
    const path = '/api/business/work-orders/WO1/inbound';
    expect(mayReadBusinessRoute(current, path)).toBe(true);
    expect(mayWriteBusinessRoute(current, path + '/dispatch')).toBe(true);
    expect(mayWriteBusinessRoute(current, path + '/tasks')).toBe(false);
    expect(mayWriteBusinessRoute({ ...current, readOnly: true, superAdmin: true }, path + '/dispatch')).toBe(false);
  });
  it('accepts three-decimal tons and rejects fractional bundles, fourth decimals and incomplete shuttles', () => {
    const line = { lineId: '1', goodCode: 'STEEL', plannedQty: 1.025, unit: 't' };
    for (const unit of ['吨', 't', 'ton']) expect(inboundLine.safeParse({ ...line, unit }).success).toBe(true);
    expect(inboundLine.safeParse({ ...line, plannedQty: 1.0251 }).success).toBe(false);
    expect(inboundLine.safeParse({ ...line, unit: '捆' }).success).toBe(false);
    expect(inboundLine.safeParse({ ...line, unit: '件', plannedQty: 2 }).success).toBe(true);
    const details = { plannedWorkAt: '2026-11-08T16:00:00+08:00', operationLocation: 'T1', channel: '通道1',
      transferMode: 'SHUTTLE', lines: [line] };
    expect(workOrderDispatchDetails.safeParse(details).success).toBe(false);
    expect(workOrderDispatchDetails.safeParse({ ...details, vehiclePlate: '测试车牌', driverName: '联调司机' }).success).toBe(true);
  });
  it('persists loading details and exports actual plan context, exact unit and batch from the saved order', async () => {
    vi.stubEnv('PSMS_WMS_COMPANY_CODE', 'TEST-C'); vi.stubEnv('PSMS_WMS_OWNER_CODE', 'TEST-O');
    const dispatchDetails = { plannedWorkAt: '2026-11-08T16:00:00+08:00', operationLocation: '装车点1', channel: '通道1',
      transferMode: 'DIRECT' as const, sourceWarehouseCode: 'SOURCE-W',
      lines: [{ lineId: '1', goodCode: 'STEEL', plannedQty: 1.125, unit: '吨', batchCode: 'ACTUAL-B1' }] };
    const result = await createBusinessWorkOrder({ planId: 'P1', orderType: 'LOADING', priority: 'MEDIUM', description: '装车派遣工单', dispatchDetails }, actor, connect);
    const exported = await exportDispatchDetails(result.item.id, actor, connect);
    expect(exported).toMatchObject({ workOrderId: result.item.id, direction: 'OUTBOUND', trainNo: 'TEST-01', workArea: 'AREA-A',
      companyCode: 'TEST-C', ownerCode: 'TEST-O', sourceWarehouseCode: 'SOURCE-W', lines: dispatchDetails.lines });
    const version = result.item.record_version;
    await expect(saveDispatchDetails(result.item.id, { expectedVersion: version, dispatchDetails }, { ...actor, dataScope: ['AREA-B'] }, connect)).rejects.toMatchObject({ statusCode: 404 });
    const changed = { ...dispatchDetails, operationLocation: '装车点2' };
    await saveDispatchDetails(result.item.id, { expectedVersion: version, dispatchDetails: changed }, actor, connect);
    expect((await exportDispatchDetails(result.item.id, actor, connect)).operationLocation).toBe('装车点2');
    await expect(saveDispatchDetails(result.item.id, { expectedVersion: version, dispatchDetails }, actor, connect)).rejects.toMatchObject({ statusCode: 409 });
    expect(validateDispatchDirection('LOADING', { ...dispatchDetails, sourceWarehouseCode: undefined })).toContain('来源仓');
  });
  it('uses persisted inbound lines and adds 0.400 and 0.625 tons without float drift', async () => {
    const dispatchDetails = { plannedWorkAt: '2026-11-08T16:00:00+08:00', expectedArrivalAt: '2026-11-08T15:00:00+08:00',
      operationLocation: '卸车点1', channel: '通道1', transferMode: 'DIRECT' as const,
      lines: [{ lineId: '1', goodCode: 'FLY-ASH', plannedQty: 1.025, unit: 't', batchCode: 'ACTUAL-B2' }] };
    const created = await createBusinessWorkOrder({ planId: 'P1', orderType: 'UNLOADING', priority: 'MEDIUM', description: '吨收货联调', dispatchDetails }, actor, connect);
    const { expectedArrivalAt, lines } = dispatchDetails;
    const request = { expectedVersion: created.item.record_version, expectedArrivalAt, lines };
    await expect(service.dispatch(created.item.id, { ...request, lines: [{ ...lines[0], plannedQty: 2 }] }, actor)).rejects.toThrow('已保存');
    const task = await service.dispatch(created.item.id, request, actor);
    expect(task.payload.companyCode).toBe('TEST-C');
    await expect(saveDispatchDetails(created.item.id, { expectedVersion: created.item.record_version, dispatchDetails }, actor, connect)).rejects.toThrow('人工协调');
    const base = { schemaVersion: '1.0', taskId: task.task_id, version: 1, occurredAt: '2026-11-08T16:00:00+08:00', warehouseCode: 'WH2', wmsOrderCode: 'IN2' };
    await service.receive({ ...base, sequence: 1, eventId: 'TON-WH', eventType: 'WAREHOUSE_CONFIRMED' });
    for (const [sequence, delta, received, remaining] of [[2, 0.4, 0.4, 0.625], [3, 0.625, 1.025, 0]]) {
      await service.receive({ ...base, sequence, eventId: 'TON-' + sequence, receiptOperationCode: 'TON-OP-' + sequence,
        eventType: sequence === 3 ? 'FULLY_RECEIVED' : 'PARTIALLY_RECEIVED',
        lines: [{ lineId: '1', goodCode: 'FLY-ASH', unit: 't', receivedDelta: delta, receivedQty: received, remainingQty: remaining }] });
    }
    expect((await service.detail(created.item.id, actor)).task.progress).toEqual([{ lineId: '1', receivedQty: 1.025, remainingQty: 0 }]);
  });
});
