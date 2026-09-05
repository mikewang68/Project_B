import { useCallback, useEffect, useRef, useState } from 'react';

import type { ApiErrorEnvelope, ApiSuccessEnvelope, PublicErrorCode } from '../../contracts';

export type PlanEntryReadState = Readonly<{
  state: 'loading' | 'data' | 'business-error' | 'network-error';
  errorCode?: PublicErrorCode;
}>;

export type RefreshPlanEntryReadStateOptions = Readonly<{
  showLoading?: boolean;
}>;

export type UsePlanEntryReadStateOptions = Readonly<{
  enabled?: boolean;
  recoverableErrorCode?: PublicErrorCode;
}>;

type ReadEnvelope = ApiSuccessEnvelope | ApiErrorEnvelope;

function classifyReadEnvelope(
  envelope: ReadEnvelope,
  recoverableErrorCode?: PublicErrorCode,
): PlanEntryReadState {
  if (envelope.ok || envelope.errorCode === recoverableErrorCode) {
    return { state: 'data' };
  }
  if (envelope.errorCode === 'TOS-EXT-001') {
    return { state: 'network-error', errorCode: envelope.errorCode };
  }
  return { state: 'business-error', errorCode: envelope.errorCode };
}

export function usePlanEntryReadState(
  load: () => Promise<ReadEnvelope>,
  {
    enabled = true,
    recoverableErrorCode,
  }: UsePlanEntryReadStateOptions = {},
): Readonly<{
  readState: PlanEntryReadState;
  refresh: (options?: RefreshPlanEntryReadStateOptions) => Promise<void>;
}> {
  const loadRef = useRef(load);
  loadRef.current = load;
  const mountedRef = useRef(false);
  const initialLoadStartedRef = useRef(false);
  const inFlightRef = useRef<Promise<void> | undefined>(undefined);
  const [readState, setReadState] = useState<PlanEntryReadState>(
    enabled ? { state: 'loading' } : { state: 'data' },
  );

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const refresh = useCallback(
    async ({ showLoading = true }: RefreshPlanEntryReadStateOptions = {}) => {
      if (inFlightRef.current) return inFlightRef.current;
      if (showLoading && mountedRef.current) setReadState({ state: 'loading' });

      const request = (async () => {
        try {
          const envelope = await loadRef.current();
          if (mountedRef.current) {
            setReadState(classifyReadEnvelope(envelope, recoverableErrorCode));
          }
        } catch {
          if (mountedRef.current) setReadState({ state: 'network-error' });
        } finally {
          inFlightRef.current = undefined;
        }
      })();
      inFlightRef.current = request;
      return request;
    },
    [recoverableErrorCode],
  );

  useEffect(() => {
    if (!enabled || initialLoadStartedRef.current) return;
    initialLoadStartedRef.current = true;
    void refresh();
  }, [enabled, refresh]);

  return { readState, refresh };
}
