import { createStore } from 'zustand/vanilla';

import type { FixtureSnapshot } from '../mocks/fixtures';
import { createInitialState, validateDemoRootState } from './initialState';
import type { DemoRootState, DemoSessionSeed, DemoStoreApi, DomainStateMutator } from './types';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;

  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

export function createDemoStore(
  snapshot: FixtureSnapshot,
  session: DemoSessionSeed,
): DemoStoreApi {
  const store = createStore<DemoRootState>()(() => deepFreeze(createInitialState(snapshot, session)));

  const replaceDomainState = (mutator: DomainStateMutator): void => {
    const candidate = structuredClone(store.getState());
    mutator(candidate);
    const validated = deepFreeze(validateDemoRootState(candidate));
    store.setState(validated, true);
  };

  const resetFromSnapshot = (
    nextSnapshot: FixtureSnapshot,
    nextSession: DemoSessionSeed,
  ): void => {
    const candidate = deepFreeze(createInitialState(nextSnapshot, nextSession));
    store.setState(candidate, true);
  };

  return Object.assign(store, { replaceDomainState, resetFromSnapshot });
}
