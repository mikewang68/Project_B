import { Card, Input } from 'antd';

import {
  auditResultCategories,
  auditSourceModules,
  type AuditFilters,
  type AuditResultCategory,
  type AuditSourceModule,
} from '../auditTypes';
import { businessLabel } from '../../../presentation/businessCopy';

const resultLabels: Record<AuditResultCategory, string> = {
  RECORDED: '已记录',
  SUCCESS: '显式成功',
  DENIED: '权限拒绝',
  VERSION_CONFLICT: '版本冲突',
  IDEMPOTENT_HIT: '幂等命中',
  BUSINESS_ERROR: '业务错误',
};

export default function AuditFilterBar({
  filters,
  pending,
  onFilters,
}: Readonly<{
  filters: AuditFilters;
  pending: boolean;
  onFilters: (filters: AuditFilters) => void;
}>) {
  const textFilter = (key: keyof AuditFilters, value: string) => onFilters({
    ...filters,
    [key]: value || undefined,
  });
  return (
    <Card className="audit-filter-card" size="small" title="组合筛选" aria-label="审计筛选">
      <div className="audit-filter-grid">
        <label>
          <span>来源模块</span>
          <select
            aria-label="来源模块"
            disabled={pending}
            value={filters.module ?? ''}
            onChange={(event: { target: { value: string } }) => onFilters({
              ...filters,
              module: (event.target.value || undefined) as AuditSourceModule | undefined,
            })}
          >
            <option value="">全部来源</option>
            {auditSourceModules.map((module) => (
              <option value={module} key={module}>{businessLabel(module)}</option>
            ))}
          </select>
        </label>
        <label>
          <span>业务动作</span>
          <Input aria-label="业务动作" disabled={pending} value={filters.action ?? ''} onChange={(event: { target: { value: string } }) => textFilter('action', event.target.value)} />
        </label>
        <label>
          <span>对象类型</span>
          <Input aria-label="对象类型" disabled={pending} value={filters.objectType ?? ''} onChange={(event: { target: { value: string } }) => textFilter('objectType', event.target.value)} />
        </label>
        <label>
          <span>结果分类</span>
          <select
            aria-label="结果分类"
            disabled={pending}
            value={filters.result ?? ''}
            onChange={(event: { target: { value: string } }) => onFilters({
              ...filters,
              result: (event.target.value || undefined) as AuditResultCategory | undefined,
            })}
          >
            <option value="">全部结果</option>
            {auditResultCategories.map((result) => (
              <option value={result} key={result}>{resultLabels[result]}</option>
            ))}
          </select>
        </label>
        <label>
          <span>操作人编号</span>
          <Input aria-label="操作人编号" disabled={pending} value={filters.actorId ?? ''} onChange={(event: { target: { value: string } }) => textFilter('actorId', event.target.value)} />
        </label>
        <label>
          <span>对象编号</span>
          <Input aria-label="对象编号" disabled={pending} value={filters.objectId ?? ''} onChange={(event: { target: { value: string } }) => textFilter('objectId', event.target.value)} />
        </label>
        <label>
          <span>链路编号</span>
          <Input aria-label="链路编号" disabled={pending} value={filters.traceId ?? ''} onChange={(event: { target: { value: string } }) => textFilter('traceId', event.target.value)} />
        </label>
        <label>
          <span>发生时间</span>
          <Input aria-label="发生时间" disabled={pending} value={filters.period ?? ''} onChange={(event: { target: { value: string } }) => textFilter('period', event.target.value)} />
        </label>
      </div>
    </Card>
  );
}
