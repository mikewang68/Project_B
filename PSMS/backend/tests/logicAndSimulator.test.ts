import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
vi.mock('../src/config/env.js', () => ({ env: {} }));
vi.mock('../src/db/openGaussClient.js', () => ({ getPool: vi.fn() }));
import { CHAIN_ACTIONS } from '../src/services/writeLog.service.js';

describe('PSMS integration contracts', () => {
  it('keeps contract calls limited to business state changes', () => {
    expect(CHAIN_ACTIONS.has('plan:confirm')).toBe(true);
    expect(CHAIN_ACTIONS.has('task:decompose')).toBe(true);
    expect(CHAIN_ACTIONS.has('HTTP')).toBe(false);
    expect(CHAIN_ACTIONS.has('plan:list')).toBe(false);
  });
  it('uses the S1 simulator point contract', () => {
    const source = readFileSync(new URL('../src/scripts/s1-simulator.ts', import.meta.url), 'utf8');
    expect(source).toContain("pointCode: 'LOAD'");
    expect(source).toContain("sourceSystem: 'S1-SIMULATOR'");
  });
});
