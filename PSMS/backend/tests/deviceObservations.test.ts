import { describe, expect, it, vi } from 'vitest';
import { attachObservations } from '../integrations/psms_history/adapter.mjs';

describe('preserved production equipment observations', () => {
  it('does not read historical observations for unrelated areas', async () => {
    const query = vi.fn();
    const rows = [{ id: 'other', work_area: 'AREA-A' }];
    expect(await attachObservations({ query } as never, rows)).toBe(rows);
    expect(query).not.toHaveBeenCalled();
  });
  it('only enriches scoped simulation equipment and preserves provenance', async () => {
    const query = vi.fn()
      .mockResolvedValueOnce({ rows: [{ device_id: 'sim', event_id: 'event', wire_sha256: 'sha', observed_ms: '123', source_ms: null }] })
      .mockResolvedValueOnce({ rows: [{ point_code: 'weight', value_json: '12', unit_json: '"kg"', quality_code: 'GOOD', provenance_json: '{"simulated":true}' }] });
    const other = { id: 'other', work_area: 'AREA-A' };
    const rows = await attachObservations({ query } as never, [other, { id: 'sim', work_area: 'AREA-SIM-YAN-EQUIPMENT' }]);
    expect(rows[0]).toBe(other);
    expect(query.mock.calls[0][1][0]).toEqual(['sim']);
    expect(rows[1]).toMatchObject({ simulated: true, deviceObservation: {
      sourceEventId: 'event', sourceTsMs: null, points: [{ value: 12, unit: 'kg', quality_code: 'GOOD', provenance: { simulated: true } }],
    } });
    expect(query.mock.calls.every(([sql]) => String(sql).startsWith('SELECT'))).toBe(true);
  });
});
