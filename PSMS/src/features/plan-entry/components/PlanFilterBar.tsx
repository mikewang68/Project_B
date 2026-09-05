import { Button, DatePicker, Form, Input, Select, Space } from 'antd';
import dayjs from 'dayjs';

import { planStatuses } from '../../../contracts';
import { businessLabel } from '../../../presentation/businessCopy';
import { planExceptionFilterValues, type PlanEntryQuery } from '../types';

export type PlanFilterBarProps = {
  query: PlanEntryQuery;
  onChange: (query: PlanEntryQuery) => void;
};

export default function PlanFilterBar({ query, onChange }: PlanFilterBarProps) {
  const emit = (patch: Partial<PlanEntryQuery>): void => {
    onChange({ ...query, ...patch, page: 1, planId: undefined });
  };

  const reset = (): void => {
    emit({
      planBatchNo: '',
      trainNo: '',
      statuses: [],
      exceptionTypes: [],
      sort: 'updatedAt:desc',
    });
  };

  return (
    <Form className="plan-filter-bar" layout="vertical" aria-label="计划筛选">
      <div className="plan-filter-grid">
        <Form.Item label="计划批次" htmlFor="plan-batch-filter">
          <Input
            id="plan-batch-filter"
            aria-label="计划批次"
            allowClear
            value={query.planBatchNo}
            placeholder="输入批次号"
            onChange={(event: { target: { value: string } }) =>
              emit({ planBatchNo: event.target.value })
            }
          />
        </Form.Item>
        <Form.Item label="车次" htmlFor="train-filter">
          <Input
            id="train-filter"
            aria-label="车次"
            allowClear
            value={query.trainNo}
            placeholder="输入车次"
            onChange={(event: { target: { value: string } }) =>
              emit({ trainNo: event.target.value })
            }
          />
        </Form.Item>
        <Form.Item label="时间范围" htmlFor="date-filter">
          <DatePicker
            id="date-filter"
            aria-label="时间范围"
            allowClear={false}
            value={dayjs(query.date)}
            format="YYYY-MM-DD"
            onChange={(value: { format: (template: string) => string } | null) => {
              if (value) emit({ date: value.format('YYYY-MM-DD') });
            }}
          />
        </Form.Item>
        <Form.Item label="状态" htmlFor="status-filter">
          <Select
            id="status-filter"
            aria-label="状态"
            mode="multiple"
            value={[...query.statuses]}
            maxTagCount="responsive"
            options={planStatuses.map((status) => ({ label: businessLabel(status), value: status }))}
            onChange={(statuses: string[]) => emit({ statuses })}
          />
        </Form.Item>
        <Form.Item label="异常类型" htmlFor="exception-filter">
          <Select
            id="exception-filter"
            aria-label="异常类型"
            mode="multiple"
            value={[...query.exceptionTypes]}
            maxTagCount="responsive"
            options={planExceptionFilterValues.map((type) => ({ label: businessLabel(type), value: type }))}
            onChange={(exceptionTypesValue: string[]) =>
              emit({ exceptionTypes: exceptionTypesValue })
            }
          />
        </Form.Item>
      </div>
      <Space className="plan-filter-actions">
        <Button onClick={reset}>重置筛选</Button>
      </Space>
    </Form>
  );
}
