import { arrayMove } from '@dnd-kit/sortable';
import { Alert, Button, Card, Input, Select, Space, Statistic, Tag, Typography } from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { authorize } from '../../auth';
import type { Appointment } from '../../contracts';
import PageIdentity from '../../features/plan-entry/components/PageIdentity';
import {
  activeQueueAppointments,
  appointmentAction,
  appointmentLocation,
  createRoadAppointmentGateway,
  displayDriver,
  displayVehicleNo,
  SortableQueue,
  YardMap,
} from '../../features/road-appointment';
import '../../features/road-appointment/road-appointment.css';
import { businessLabel, businessTone } from '../../presentation/businessCopy';
import { useDemoSelector } from '../../runtime';

const statusOptions = [
  '全部状态', '排队中', '已叫号', '已入场', '作业中', '已放行', '已离场',
].map((label) => ({ label, value: label }));

const toneColor = {
  success: 'success', processing: 'processing', warning: 'warning', danger: 'error', neutral: 'default',
} as const;

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date);
}

export default function RoadAppointmentPage() {
  const storeAppointments = useDemoSelector((state) => state.vehicle.appointments);
  const session = useDemoSelector((state) => state.session);
  const activeScenarioId = useDemoSelector((state) => state.scenario.activeScenarioId);
  const gateway = useMemo(() => createRoadAppointmentGateway(), []);
  const [appointments, setAppointments] = useState<Appointment[]>(() => structuredClone(storeAppointments));
  const [queueIds, setQueueIds] = useState<string[]>(() =>
    activeQueueAppointments(storeAppointments).map(({ id }) => id));
  const [selectedId, setSelectedId] = useState(storeAppointments[0]?.id);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('全部状态');
  const [loadingId, setLoadingId] = useState<string>();
  const [notice, setNotice] = useState<{ type: 'success' | 'warning'; text: string }>();
  const [apiChecked, setApiChecked] = useState(false);

  useEffect(() => {
    let active = true;
    void gateway.listAppointments(session.roleCode)
      .then((result) => {
        if (!active) return;
        setApiChecked(result.ok && result.scenarioId === activeScenarioId);
        if (!result.ok) setNotice({ type: 'warning', text: '预约接口暂不可用，当前展示本地固定数据。' });
      })
      .catch(() => {
        if (active) setNotice({ type: 'warning', text: '预约接口暂不可用，当前展示本地固定数据。' });
      });
    return () => { active = false; };
  }, [activeScenarioId, gateway, session.roleCode]);

  const selected = appointments.find(({ id }) => id === selectedId) ?? appointments[0];
  const queuedAppointments = queueIds
    .map((id) => appointments.find((appointment) => appointment.id === id))
    .filter((appointment): appointment is Appointment => appointment !== undefined);
  const filtered = appointments.filter((appointment) => {
    const search = keyword.trim().toLowerCase();
    const matchesKeyword = !search || [
      appointment.reservationNo,
      displayVehicleNo(appointment.vehicleNo),
      displayDriver(appointment.driverId),
      appointment.queueNo,
    ].some((value) => value.toLowerCase().includes(search));
    const matchesStatus = statusFilter === '全部状态'
      || businessLabel(appointment.status) === statusFilter;
    return matchesKeyword && matchesStatus;
  });

  const selectAppointment = useCallback((appointmentId: string) => {
    setSelectedId(appointmentId);
    setNotice(undefined);
  }, []);

  const reorder = (activeId: string, overId: string) => {
    setQueueIds((current) => {
      const from = current.indexOf(activeId);
      const to = current.indexOf(overId);
      if (from < 0 || to < 0) return current;
      setNotice({ type: 'success', text: '候车顺序已在本机调整。' });
      return arrayMove(current, from, to);
    });
  };

  const runTransition = async () => {
    if (!selected) return;
    const action = appointmentAction(selected.status);
    if (!action) return;
    const decision = authorize({
      session,
      pageId: 'UI-006',
      permission: action.permission,
      objectScope: { type: 'AREA', value: 'AREA-A' },
    });
    if (!decision.allow) {
      setNotice({ type: 'warning', text: '当前角色或在线状态不允许执行此操作。' });
      return;
    }
    setLoadingId(selected.id);
    setNotice(undefined);
    try {
      const result = await gateway.transitionAppointment(selected.id, {
        action: action.code,
        evidence: { queueNo: selected.queueNo, operator: session.actorId },
      });
      if (!result.ok) {
        setNotice({ type: 'warning', text: `状态更新失败：${result.errorCode}` });
        return;
      }
      setAppointments((current) => current.map((appointment) => appointment.id === selected.id
        ? { ...appointment, status: action.next, version: appointment.version + 1, updatedAt: result.now }
        : appointment));
      if (action.next === 'EXITED' || action.next === 'RELEASED') {
        setQueueIds((current) => current.filter((id) => id !== selected.id));
      } else {
        setQueueIds((current) => current.includes(selected.id) ? current : [...current, selected.id]);
      }
      setNotice({ type: 'success', text: `${displayVehicleNo(selected.vehicleNo)} 已更新为“${businessLabel(action.next)}”。` });
    } catch {
      setNotice({ type: 'warning', text: '状态更新请求未完成，请稍后重试。' });
    } finally {
      setLoadingId(undefined);
    }
  };

  const nextAction = selected ? appointmentAction(selected.status) : undefined;
  const permissionDecision = selected && nextAction ? authorize({
    session,
    pageId: 'UI-006',
    permission: nextAction.permission,
    objectScope: { type: 'AREA', value: 'AREA-A' },
  }) : undefined;

  return (
    <main className="road-appointment-page" aria-label="UI-006 公路预约与叫号页面">
      <header className="road-appointment-header">
        <PageIdentity pageId="UI-006" name="公路预约与叫号" path="/yard/appointments" />
        <Space size={8} wrap>
          <Tag color={apiChecked ? 'success' : 'default'}>{apiChecked ? '预约接口已校验' : '本地数据就绪'}</Tag>
          <Tag color="processing">{businessLabel(session.roleCode)}</Tag>
          <Tag>{activeScenarioId}</Tag>
        </Space>
      </header>

      {notice ? <Alert showIcon type={notice.type} title={notice.text} closable onClose={() => setNotice(undefined)} /> : null}

      <section className="appointment-kpis" aria-label="预约运行指标">
        <Card size="small"><Statistic title="今日预约" value={appointments.length} suffix="辆" /></Card>
        <Card size="small"><Statistic title="候车队列" value={queuedAppointments.length} suffix="辆" /></Card>
        <Card size="small"><Statistic title="场内作业" value={appointments.filter(({ status }) => status === 'OPERATING').length} suffix="辆" /></Card>
        <Card size="small"><Statistic title="已放行离场" value={appointments.filter(({ status }) => ['RELEASED', 'EXITED'].includes(status)).length} suffix="辆" /></Card>
      </section>

      <section className="appointment-primary-grid">
        <Card title="候车叫号队列" extra={<Typography.Text type="secondary">拖动调整顺序</Typography.Text>}>
          <SortableQueue
            appointments={queuedAppointments}
            selectedId={selected?.id}
            onSelect={selectAppointment}
            onReorder={reorder}
          />
        </Card>
        <Card title="车辆路线与场区位置" extra={<Tag>本地场区数据</Tag>}>
          <YardMap appointments={appointments} selectedId={selected?.id} onSelect={selectAppointment} />
          <div className="yard-map-legend" aria-label="场区图例">
            <span><i className="legend-route" />车辆行驶路线</span>
            <span><i className="legend-vehicle" />车辆位置</span>
            <span><i className="legend-selected" />当前选中车辆</span>
          </div>
        </Card>
        <Card title="当前车辆" className="appointment-detail-card">
          {selected ? (
            <div className="appointment-detail" aria-label="当前预约详情">
              <div><span>车辆</span><strong>{displayVehicleNo(selected.vehicleNo)}</strong></div>
              <div><span>预约单</span><strong>{selected.reservationNo}</strong></div>
              <div><span>候车号</span><strong>{selected.queueNo}</strong></div>
              <div><span>驾驶员</span><strong>{displayDriver(selected.driverId)}</strong></div>
              <div><span>当前位置</span><strong>{appointmentLocation(appointments.findIndex(({ id }) => id === selected.id))}</strong></div>
              <div><span>门闸状态</span><strong>{selected.gateStatus === 'OPEN' ? '闸门开启' : businessLabel(selected.gateStatus)}</strong></div>
              <div className="appointment-detail-status">
                <span>预约状态</span>
                <Tag color={toneColor[businessTone(selected.status)]}>{businessLabel(selected.status)}</Tag>
              </div>
              {nextAction ? (
                <Button type="primary" block loading={loadingId === selected.id} disabled={permissionDecision ? !permissionDecision.allow : true} onClick={() => void runTransition()}>
                  {nextAction.label}
                </Button>
              ) : <Alert type="success" showIcon title="该车辆流程已完成" />}
              {permissionDecision && !permissionDecision.allow ? (
                <Typography.Text type="secondary">当前角色或在线状态不能执行下一步操作。</Typography.Text>
              ) : null}
            </div>
          ) : null}
        </Card>
      </section>

      <Card title="预约台账" className="appointment-ledger-card">
        <div className="appointment-filters">
          <Input allowClear aria-label="搜索预约" placeholder="搜索预约单、车辆、驾驶员或候车号" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
          <Select aria-label="预约状态" value={statusFilter} options={statusOptions} onChange={setStatusFilter} />
          <Typography.Text type="secondary">共 {filtered.length} 条</Typography.Text>
        </div>
        <div className="appointment-table-wrap">
          <table className="appointment-table" aria-label="预约台账">
            <thead><tr><th>候车号</th><th>车辆</th><th>预约单</th><th>驾驶员</th><th>状态</th><th>位置</th><th>更新时间</th><th>操作</th></tr></thead>
            <tbody>
              {filtered.map((appointment, index) => (
                <tr key={appointment.id} className={appointment.id === selected?.id ? 'is-selected' : ''}>
                  <td><strong>{appointment.queueNo}</strong></td>
                  <td>{displayVehicleNo(appointment.vehicleNo)}</td>
                  <td><code>{appointment.reservationNo}</code></td>
                  <td>{displayDriver(appointment.driverId)}</td>
                  <td><Tag color={toneColor[businessTone(appointment.status)]}>{businessLabel(appointment.status)}</Tag></td>
                  <td>{appointmentLocation(index)}</td>
                  <td>{formatTime(appointment.updatedAt)}</td>
                  <td><Button size="small" onClick={() => selectAppointment(appointment.id)}>查看</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </main>
  );
}
