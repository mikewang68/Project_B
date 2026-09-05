import { describe, expect, it } from 'vitest';

import { parsePlanEntryQuery, serializePlanEntryQuery } from '../query';
import type { PlanEntryQuery } from '../types';

const completeQuery: PlanEntryQuery = {
  date: '2026-07-17',
  workArea: 'AREA-A',
  scenarioId: 'SCN-02',
  planBatchNo: 'PB-20260716-02',
  trainNo: '75002',
  statuses: ['RECEIVED', 'BLOCKED', 'RECEIVED'],
  exceptionTypes: ['TIMEOUT', 'VALIDATION_MISSING_FIELD', 'DATA_CONFLICT'],
  page: 2,
  pageSize: 20,
  sort: 'status:asc',
  planId: 'PLAN-002',
  from: 'overview',
};

describe('plan entry query codec', () => {
  it('falls back to the exact defaults for malformed values', () => {
    const parsed = parsePlanEntryQuery(
      'date=not-a-date&workArea=outside&scenarioId=SCN-99&page=0&pageSize=50&sort=nope&from=elsewhere',
    );

    expect(parsed).toEqual({
      date: '2026-07-16',
      workArea: 'AREA-A',
      scenarioId: 'SCN-01',
      planBatchNo: '',
      trainNo: '',
      statuses: [],
      exceptionTypes: [],
      page: 1,
      pageSize: 20,
      sort: 'updatedAt:desc',
    });
  });

  it('serializes every supported field in a stable order and round-trips normalized arrays', () => {
    const serialized = serializePlanEntryQuery(completeQuery);

    expect(serialized.toString()).toBe(
      'date=2026-07-17&workArea=AREA-A&scenarioId=SCN-02&planBatchNo=PB-20260716-02&trainNo=75002&status=BLOCKED&status=RECEIVED&exceptionType=DATA_CONFLICT&exceptionType=TIMEOUT&exceptionType=VALIDATION_MISSING_FIELD&page=2&pageSize=20&sort=status%3Aasc&planId=PLAN-002&from=overview',
    );
    expect(parsePlanEntryQuery(serialized)).toEqual({
      ...completeQuery,
      statuses: ['BLOCKED', 'RECEIVED'],
      exceptionTypes: ['DATA_CONFLICT', 'TIMEOUT', 'VALIDATION_MISSING_FIELD'],
    });
  });

  it('omits empty and default-only filters while retaining required navigation context', () => {
    const serialized = serializePlanEntryQuery(parsePlanEntryQuery(''));

    expect(serialized.toString()).toBe('date=2026-07-16&workArea=AREA-A&scenarioId=SCN-01');
  });
});
