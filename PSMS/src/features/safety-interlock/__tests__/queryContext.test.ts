import { describe, expect, it } from 'vitest';

import {
  C09_AUDIT_PREFIX,
  c09ProgressStates,
  SAFETY_INTERLOCK_FEATURE,
} from '../constants';
import { parseInterlockQueryContext } from '../queryContext';

describe('C09 safety interlock query context', () => {
  it('keeps the frozen feature constants stable', () => {
    expect(SAFETY_INTERLOCK_FEATURE).toBe('C09');
    expect(C09_AUDIT_PREFIX).toBe('SI');
    expect(c09ProgressStates).toEqual(['LOCKED', 'RESETTING', 'RESTORED', 'OVERRIDE']);
  });

  it('parses C08 source context and supported interlock filters', () => {
    const context = parseInterlockQueryContext(
      '?exceptionId=EX-003'
      + '&scenarioId=SCN-01'
      + '&from=exception-handling'
      + '&status=APPROVED'
      + '&actionLevel=FORCE_STOP'
      + '&riskType=DEVICE_FAULT'
      + '&receiptStatus=FAILED',
    );

    expect(context).toEqual({
      exceptionId: 'EX-003',
      scenarioId: 'SCN-01',
      from: 'exception-handling',
      status: 'APPROVED',
      actionLevel: 'FORCE_STOP',
      riskType: 'DEVICE_FAULT',
      receiptStatus: 'FAILED',
    });
    expect(Object.isFrozen(context)).toBe(true);
  });

  it('returns safe display strings and ignores invalid enum filters', () => {
    const longRiskType = `  RISK-${'A'.repeat(180)}\u0000  `;
    const context = parseInterlockQueryContext(new URLSearchParams({
      exceptionId: '  __proto__  ',
      scenarioId: ' SCN-01\n ',
      from: ' exception-handling ',
      status: 'NOT_A_STATUS',
      actionLevel: 'NOT_AN_ACTION_LEVEL',
      riskType: longRiskType,
      receiptStatus: 'NOT_A_RECEIPT_STATUS',
    }));

    expect(context.exceptionId).toBe('__proto__');
    expect(context.scenarioId).toBe('SCN-01');
    expect(context.from).toBe('exception-handling');
    expect(context.status).toBeUndefined();
    expect(context.actionLevel).toBeUndefined();
    expect(context.receiptStatus).toBeUndefined();
    expect(context.riskType).toHaveLength(128);
    expect(context.riskType).not.toContain('\u0000');
  });

  it('never resolves source context or invents a DO-010 foreign key', () => {
    const context = parseInterlockQueryContext(
      '?exceptionId=EX-DOES-NOT-EXIST&scenarioId=SCN-DOES-NOT-EXIST&workOrderId=WO-001',
    );

    expect(context).toEqual({
      exceptionId: 'EX-DOES-NOT-EXIST',
      scenarioId: 'SCN-DOES-NOT-EXIST',
    });
    expect(context).not.toHaveProperty('exception');
    expect(context).not.toHaveProperty('interlockId');
    expect(context).not.toHaveProperty('workOrderId');
  });
});
