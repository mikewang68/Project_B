import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UserContext } from '@bproject/iam-context-client';
import { runWithCentralScope } from '../src/middleware/central-scope.js';
import { assertLegacyBackground } from '../src/services/background-scope-policy.js';
import { RailwayRuntime } from '../src/services/railway-runtime.service.js';
import { startRailwayWorker } from '../src/services/railway-scheduler.service.js';
vi.mock('../src/db/openGaussClient.js', () => ({ connectClient: vi.fn(() => { throw new Error('No real database in this test'); }) }));
vi.mock('../src/lib/logger.js', () => ({ logger: { error: vi.fn() } }));
afterEach(() => { vi.unstubAllEnvs(); vi.useRealTimers(); });

describe('legacy background startup contract', () => {
  it('preserves unset legacy defaults for both workers', () => {
    for (const family of ['RAILWAY', 'SIMULATOR'] as const)
      expect(() => assertLegacyBackground(family, {})).not.toThrow();
  });
  it('permits explicitly unassigned legacy labels only', () => {
    expect(() => assertLegacyBackground('RAILWAY', { PSMS_RAILWAY_AUTH_MODE:'LEGACY_ONLY',
      PSMS_RAILWAY_PROJECT_ID:'LEGACY', PSMS_RAILWAY_REGION_ID:'UNASSIGNED' })).not.toThrow();
  });
  it.each(['', 'iam_v2', 'anything'])('rejects an unknown mode %j', mode => {
    expect(() => assertLegacyBackground('RAILWAY', { PSMS_RAILWAY_AUTH_MODE:mode }))
      .toThrow('BACKGROUND_AUTH_MODE_INVALID');
  });
  it.each(['RAILWAY', 'SIMULATOR'] as const)('cannot self-approve %s V2 through configuration', family => {
    expect(() => assertLegacyBackground(family, { [`PSMS_${family}_AUTH_MODE`]:'IAM_V2',
      [`PSMS_${family}_SUBJECT_ID`]:'configured-subject', [`PSMS_${family}_REGION_ID`]:'SHEN_TIE' }))
      .toThrow('BACKGROUND_V2_NOT_ADAPTED');
  });
  it.each(['PROJECT_ID', 'REGION_ID', 'SUBJECT_ID', 'SESSION_ID'])('rejects misleading legacy %s', field => {
    expect(() => assertLegacyBackground('RAILWAY', { [`PSMS_RAILWAY_${field}`]:'claimed-value' }))
      .toThrow('BACKGROUND_LEGACY_SCOPE_CONFLICT');
  });
  it('refuses startup before any database connection', async () => {
    vi.stubEnv('PSMS_RAILWAY_AUTH_MODE', 'IAM_V2'); const connect=vi.fn();
    await expect(new RailwayRuntime(connect).initialize()).rejects.toThrow('BACKGROUND_V2_NOT_ADAPTED');
    expect(connect).not.toHaveBeenCalled();
  });
  it('refuses timer registration and tick on invalid startup configuration', () => {
    vi.useFakeTimers(); vi.stubEnv('PSMS_RAILWAY_AUTH_MODE', 'IAM_V2');
    const tick=vi.fn(),report=vi.fn();
    expect(() => startRailwayWorker({ tick } as unknown as RailwayRuntime, report))
      .toThrow('BACKGROUND_V2_NOT_ADAPTED');
    expect(tick).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0);
  });
  it('checks again before a resumed transaction and rejects inherited user context', async () => {
    const connect=vi.fn(); const runtime=new RailwayRuntime(connect);
    await runWithCentralScope({contextVersion:2} as UserContext, async () => {
      await expect(runtime.tick()).rejects.toThrow('BACKGROUND_REQUEST_CONTEXT_REJECTED');
      expect(() => assertLegacyBackground('SIMULATOR', {})).toThrow('BACKGROUND_REQUEST_CONTEXT_REJECTED');
    });
    expect(connect).not.toHaveBeenCalled();
    expect(() => assertLegacyBackground('SIMULATOR', {})).not.toThrow();
  });
});
