import { z } from 'zod';

import { roleCodeSchema } from '../contracts';
import { createFixtureSnapshot } from '../mocks/fixtures';
import { DEMO_SESSION_STORAGE_KEY } from './sessionConstants';
import type { SessionContext } from './types';

export { DEMO_SESSION_STORAGE_KEY } from './sessionConstants';
export const DEFAULT_DEMO_TIME = createFixtureSnapshot().scenarios.find(
  ({ id }) => id === 'SCN-01',
)!.clock;

export type SessionStorageReader = {
  getItem: (key: string) => string | null;
};

const storedSessionSchema = z
  .object({
    actorId: z.string().min(1),
    roleCode: roleCodeSchema,
    dataScope: z.array(z.string().min(1)),
    online: z.boolean(),
  })
  .strict();

export function createDefaultSession(demoTime: string): SessionContext {
  return {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
    demoTime,
  };
}

export function loadDemoSession(
  storage: SessionStorageReader | undefined,
  demoTime: string,
): SessionContext {
  const fallback = createDefaultSession(demoTime);
  const raw = storage?.getItem(DEMO_SESSION_STORAGE_KEY);
  if (!raw) return fallback;

  try {
    const parsed = storedSessionSchema.parse(JSON.parse(raw));
    return { ...parsed, demoTime };
  } catch {
    return fallback;
  }
}

export function loadCurrentDemoSession(): SessionContext {
  const storage = typeof window === 'undefined' ? undefined : window.localStorage;
  return loadDemoSession(storage, DEFAULT_DEMO_TIME);
}
