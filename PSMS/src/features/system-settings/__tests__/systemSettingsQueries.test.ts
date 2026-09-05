import { describe, expect, it } from 'vitest';

import { parseSystemSettingsQuery } from '../index';

describe('C13 system settings query parsing', () => {
  it('normalizes the supported context values', () => {
    expect(parseSystemSettingsQuery(
      '?group=dispatch&configId=%20CFG-001%20&scenarioId=SCN-01&from=%20overview%20',
    )).toEqual({
      group: 'dispatch',
      configId: 'CFG-001',
      scenarioId: 'SCN-01',
      from: 'overview',
    });
  });

  it('uses the first repeated value, falls back unknown groups, and omits blank text', () => {
    expect(parseSystemSettingsQuery(
      '?group=unknown&group=access&configId=%20%20&scenarioId=SCN-07&scenarioId=SCN-01',
    )).toEqual({
      group: 'overview',
      scenarioId: 'SCN-07',
    });
  });

  it('removes control characters and caps display-only query values', () => {
    const from = `  dispatch\u0000-${'x'.repeat(160)}  `;
    const result = parseSystemSettingsQuery(new URLSearchParams({ from }));
    expect(result.from).not.toContain('\u0000');
    expect(result.from).toHaveLength(128);
    expect(result.group).toBe('overview');
  });
});
