import { Alert, Button, Input, Select, Space, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useDemoSelector } from '../../runtime';
import { businessLabel } from '../../presentation/businessCopy';
import { loadBusinessPage, type BusinessPage } from '../../real/business';
import { railwayApi } from '../../real/railwayClient';
import WorkOrderConsultation from './WorkOrderConsultation';
import { businessConsultation, orderSnapshot, railwayConsultation, type ConsultationOrder } from './workOrderSnapshot';

const pageSize = 50;
const live = import.meta.env.VITE_PSMS_REAL_API === 'true';

export default function WorkOrderPicker() {
  const state = useDemoSelector(current => current);
  const [id, setId] = useState<string>();
  const [page, setPage] = useState(1);
  const [records, setRecords] = useState<BusinessPage>();
  const [refresh, setRefresh] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [number, setNumber] = useState('');
  const [matched, setMatched] = useState<ConsultationOrder>();
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const searchRequest = useRef<AbortController | null>(null);
  const scope = state.session.dataScope;
  const scopeKey = scope.join('|');
  const global = scope.includes('*') || scope.includes('GLOBAL');
  const visible = (order: ConsultationOrder) => global || scope.includes(order.snapshot.workArea);

  function cancelSearch() {
    searchRequest.current?.abort();
    searchRequest.current = null;
  }
  useEffect(() => {
    setMatched(undefined);
    setSearching(false);
    return cancelSearch;
  }, [scopeKey]);

  useEffect(() => {
    if (!live) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    void loadBusinessPage('work-orders', { page: String(page), pageSize: String(pageSize) }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setRecords(result); })
      .catch(cause => {
        if (!controller.signal.aborted) {
          setRecords(undefined);
          setError(cause instanceof Error ? cause.message : '无法读取授权范围内的工单。');
        }
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [page, refresh, scopeKey]);

  const demoOrders = (): ConsultationOrder[] => state.workOrder.workOrders.flatMap(order => {
    const workArea = state.plan.plans.find(item => item.id === order.planId)?.workArea;
    if (!workArea || order.valid === 0 || (!global && !scope.includes(workArea))) return [];
    return [{
      id: order.id,
      label: order.workOrderNo + ' · ' + businessLabel(order.type),
      snapshot: orderSnapshot({
        source: 'SIMULATION', workArea, status: order.status, orderType: order.type,
        priority: order.priority, resourceAssigned: Boolean(order.resourceId || order.teamId),
        tasks: state.workOrder.nodes?.filter(node => node.valid !== 0 && node.workOrderNo === order.workOrderNo).map(node => node.status),
      }),
    }];
  });
  const orders = live ? (records?.items.map(businessConsultation) ?? []).filter(order => global || scope.includes(order.snapshot.workArea)) : demoOrders();
  const selected = orders.find(order => order.id === id) ?? (matched && visible(matched) ? matched : undefined);

  async function search() {
    const value = number.trim();
    if (!value) {
      setSearchError('请输入工单编号。');
      return;
    }
    cancelSearch();
    setId(undefined);
    setMatched(undefined);
    setSearchError('');
    const visibleMatch = orders.find(order => [order.id, order.label.split(' · ')[0]]
      .some(candidate => candidate.toUpperCase() === value.toUpperCase()));
    if (visibleMatch) {
      setSearching(false);
      setId(visibleMatch.id);
      return;
    }
    const controller = new AbortController();
    searchRequest.current = controller;
    setSearching(true);
    try {
      const rail = /^SIM-RAIL-\d{8}(?:-WO)?$/i.test(value);
      let result: ConsultationOrder | undefined;
      if (rail && (global || scope.includes('AREA-A'))) {
        const ticketId = value.toUpperCase().replace(/-WO$/, '');
        result = railwayConsultation((await railwayApi('/' + ticketId)).data, ticketId);
      } else if (live) {
        const resultPage = await loadBusinessPage('work-orders', { id: value, page: '1', pageSize: '1' }, controller.signal);
        const row = resultPage.items.find(item => item.id === value);
        if (row) result = businessConsultation(row);
      }
      if (controller.signal.aborted) return;
      if (result && visible(result)) setMatched(result);
      else setSearchError('没有找到该编号，或这张工单不在你的授权范围内。');
    } catch (cause) {
      if (!controller.signal.aborted) setSearchError(cause instanceof Error ? cause.message : '查询失败，请重试。');
    } finally {
      if (searchRequest.current === controller) {
        searchRequest.current = null;
        setSearching(false);
      }
    }
  }

  function choose(value?: string) {
    cancelSearch();
    setSearching(false);
    setSearchError('');
    setMatched(undefined);
    setId(value);
  }

  function changePage(next: number) {
    choose();
    setRecords(undefined);
    setPage(next);
  }

  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <Alert type="info" showIcon title="选一张工单，查看进度、阻塞和下一步"
      description="在派工看板或铁路工单详情中，也可以直接咨询当前工单。" />
    {error && <Alert type="error" showIcon title={error} />}
    <Space.Compact style={{ width: '100%' }}>
      <Input aria-label="工单编号" placeholder="输入完整工单编号，按回车查询" value={number} maxLength={160}
        onChange={event => setNumber(event.target.value)}
        onPressEnter={event => {
          if (!event.nativeEvent.isComposing && event.keyCode !== 229) void search();
        }} />
      <Button loading={searching} onClick={() => void search()}>查找工单</Button>
    </Space.Compact>
    {searchError && <Alert type="warning" showIcon title={searchError} />}
    <Select aria-label="选择咨询工单" placeholder="也可从列表选择" value={id}
      style={{ width: '100%' }} showSearch={{ optionFilterProp: 'label' }} loading={loading}
      options={orders.map(order => ({ value: order.id, label: order.label }))}
      onChange={choose} notFoundContent={loading ? '正在读取工单' : '没有可咨询的工单'} />
    {live && <Space wrap>
      <Button size="small" disabled={page <= 1 || loading} onClick={() => changePage(page - 1)}>上一页</Button>
      <Typography.Text>第 {page} 页</Typography.Text>
      <Button size="small" disabled={!records || page * pageSize >= records.total || loading} onClick={() => changePage(page + 1)}>下一页</Button>
      <Button size="small" disabled={loading} onClick={() => setRefresh(value => value + 1)}>刷新工单</Button>
    </Space>}
    {selected && <WorkOrderConsultation key={selected.id} order={selected} />}
  </Space>;
}
