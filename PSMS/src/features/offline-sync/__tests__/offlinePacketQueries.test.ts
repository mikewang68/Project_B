import { describe, expect, it } from 'vitest';

import { mergeStatuses } from '../../../contracts';
import { parseOfflinePacketQuery } from '../offlinePacketQueries';

describe('C10 offline packet query hydration', () => {
  it('parses all supported filters and source context', () => {
    const context = parseOfflinePacketQuery(
      '?packetId=OFF-001'
      + '&terminalId=PDA-01'
      + '&workOrderNo=WO-006'
      + '&mergeStatus=CONFLICT'
      + '&scenarioId=SCN-06'
      + '&from=monitor',
    );

    expect(context).toEqual({
      packetId: 'OFF-001',
      terminalId: 'PDA-01',
      workOrderNo: 'WO-006',
      mergeStatus: 'CONFLICT',
      scenarioId: 'SCN-06',
      from: 'monitor',
    });
    expect(Object.isFrozen(context)).toBe(true);
  });

  it.each(mergeStatuses)('accepts frozen merge status %s', (mergeStatus) => {
    expect(parseOfflinePacketQuery(`?mergeStatus=${mergeStatus}`).mergeStatus).toBe(mergeStatus);
  });

  it('sanitizes display strings, caps length, and ignores invalid status or unknown keys', () => {
    const context = parseOfflinePacketQuery(new URLSearchParams({
      packetId: '  OFF-001\u0000  ',
      terminalId: ` PDA-${'A'.repeat(180)} `,
      workOrderNo: ' WO-006\n ',
      mergeStatus: 'UNKNOWN',
      scenarioId: ' SCN-06\r ',
      from: ' exception-handling ',
      exceptionId: 'EX-003',
    }));

    expect(context.packetId).toBe('OFF-001');
    expect(context.terminalId).toHaveLength(128);
    expect(context.terminalId).not.toContain('\u0000');
    expect(context.workOrderNo).toBe('WO-006');
    expect(context.mergeStatus).toBeUndefined();
    expect(context.scenarioId).toBe('SCN-06');
    expect(context.from).toBe('exception-handling');
    expect(context).not.toHaveProperty('exceptionId');
  });

  it('returns an empty object for empty or control-only values', () => {
    expect(parseOfflinePacketQuery('?mergeStatus=UNKNOWN&packetId=%00%20')).toEqual({});
    expect(parseOfflinePacketQuery('')).toEqual({});
  });
});
