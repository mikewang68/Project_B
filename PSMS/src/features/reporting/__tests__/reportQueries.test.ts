import { describe, expect, it } from 'vitest';

import { generateStatuses, reportTypes } from '../../../contracts';
import { parseReportQuery } from '../reportQueries';

describe('C11 report query hydration', () => {
  it('parses all supported filters and source context', () => {
    const context = parseReportQuery(
      '?reportId=RP-002&reportType=DAILY&period=2026-07-17'
      + '&generateStatus=FAILED&scenarioId=SCN-01&from=monitor',
    );

    expect(context).toEqual({
      reportId: 'RP-002',
      reportType: 'DAILY',
      period: '2026-07-17',
      generateStatus: 'FAILED',
      scenarioId: 'SCN-01',
      from: 'monitor',
    });
    expect(Object.isFrozen(context)).toBe(true);
  });

  it.each(reportTypes)('accepts frozen report type %s', (reportType) => {
    expect(parseReportQuery(`?reportType=${reportType}`).reportType).toBe(reportType);
  });

  it.each(generateStatuses)('accepts frozen generate status %s', (generateStatus) => {
    expect(parseReportQuery(`?generateStatus=${generateStatus}`).generateStatus)
      .toBe(generateStatus);
  });

  it('sanitizes display strings, caps length, and ignores invalid enums and keys', () => {
    const context = parseReportQuery(new URLSearchParams({
      reportId: '  RP-002\u0000  ',
      reportType: 'UNKNOWN',
      period: ` 2026-${'7'.repeat(180)} `,
      generateStatus: 'DONE',
      scenarioId: ' SCN-01\r ',
      from: ' operation-monitor\n ',
      reportIdAlias: 'RP-INVENTED',
    }));

    expect(context.reportId).toBe('RP-002');
    expect(context.reportType).toBeUndefined();
    expect(context.period).toHaveLength(128);
    expect(context.generateStatus).toBeUndefined();
    expect(context.scenarioId).toBe('SCN-01');
    expect(context.from).toBe('operation-monitor');
    expect(context).not.toHaveProperty('reportIdAlias');
  });

  it('returns an empty object for empty or control-only values', () => {
    expect(parseReportQuery('?reportType=UNKNOWN&generateStatus=DONE&reportId=%00%20'))
      .toEqual({});
    expect(parseReportQuery('')).toEqual({});
  });
});
