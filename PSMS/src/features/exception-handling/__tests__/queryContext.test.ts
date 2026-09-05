import { describe, expect, it } from 'vitest';

import {
  C08_AUDIT_PREFIX,
  c08ProgressStates,
  EXCEPTION_HANDLING_FEATURE,
} from '../constants';
import { parseExceptionQueryContext } from '../queryContext';

describe('C08 exception query context', () => {
  it('keeps the frozen feature constants stable', () => {
    expect(EXCEPTION_HANDLING_FEATURE).toBe('C08');
    expect(C08_AUDIT_PREFIX).toBe('EX');
    expect(c08ProgressStates).toEqual(['OPEN_QUEUE', 'HANDLING', 'REVIEW', 'CLOSED']);
  });

  it('parses C07 source context and supported exception filters', () => {
    const context = parseExceptionQueryContext(
      '?workOrderId=C06-WO-PLAN-001-G001-02'
      + '&planId=PLAN-001'
      + '&scenarioId=SCN-01'
      + '&from=dispatch-board'
      + '&status=PENDING_REVIEW'
      + '&level=CRITICAL'
      + '&type=TIMEOUT'
      + '&owner=TEAM-01',
    );

    expect(context).toEqual({
      workOrderId: 'C06-WO-PLAN-001-G001-02',
      planId: 'PLAN-001',
      scenarioId: 'SCN-01',
      from: 'dispatch-board',
      status: 'PENDING_REVIEW',
      level: 'CRITICAL',
      type: 'TIMEOUT',
      owner: 'TEAM-01',
    });
    expect(Object.isFrozen(context)).toBe(true);
  });

  it('trims display strings, removes controls, caps length, and ignores invalid enum filters', () => {
    const longOwner = `  TEAM-${'A'.repeat(180)}\u0000  `;
    const context = parseExceptionQueryContext(new URLSearchParams({
      workOrderId: '  __proto__  ',
      planId: '  PLAN-001\n  ',
      scenarioId: ' SCN-01 ',
      from: ' dispatch-board ',
      status: 'NOT_A_STATUS',
      level: 'NOT_A_LEVEL',
      type: 'NOT_A_TYPE',
      owner: longOwner,
    }));

    expect(context.workOrderId).toBe('__proto__');
    expect(context.planId).toBe('PLAN-001');
    expect(context.scenarioId).toBe('SCN-01');
    expect(context.from).toBe('dispatch-board');
    expect(context.status).toBeUndefined();
    expect(context.level).toBeUndefined();
    expect(context.type).toBeUndefined();
    expect(context.owner).toHaveLength(128);
    expect(context.owner).not.toContain('\u0000');
  });

  it('returns only display context and filters without resolving or inventing object relations', () => {
    const context = parseExceptionQueryContext(
      '?workOrderId=WO-DOES-NOT-EXIST&planId=PLAN-DOES-NOT-EXIST&resourceId=RESOURCE-001',
    );

    expect(context).toEqual({
      workOrderId: 'WO-DOES-NOT-EXIST',
      planId: 'PLAN-DOES-NOT-EXIST',
    });
    expect(context).not.toHaveProperty('resourceId');
    expect(context).not.toHaveProperty('workOrder');
    expect(context).not.toHaveProperty('plan');
  });
});
