import { describe, expect, it } from 'vitest';
import { ZodError } from 'zod';

import type { ApiErrorEnvelope, ApiSuccessEnvelope } from '../../../contracts';
import {
  createPlanEntryGateway,
  type Api003Request,
  type Api004Request,
  type Api025Request,
} from '../gateway';

const successEnvelope: ApiSuccessEnvelope = {
  ok: true,
  data: { plans: [] },
  auditLogId: 'AUDIT-001',
  traceId: 'TRACE-001',
};

const errorEnvelope: ApiErrorEnvelope = {
  ok: false,
  errorCode: 'TOS-AUTH-001',
  message: 'forbidden',
  details: { roleCode: 'DRIVER' },
  auditLogId: 'AUDIT-002',
  traceId: 'TRACE-002',
};

type FetchCall = {
  input: RequestInfo | URL;
  init: RequestInit | undefined;
};

function createFetcher(payloads: unknown[]): { fetcher: typeof fetch; calls: FetchCall[] } {
  const calls: FetchCall[] = [];
  let responseIndex = 0;
  const fetcher: typeof fetch = async (input, init) => {
    calls.push({ input, init });
    const payload = payloads[responseIndex];
    responseIndex += 1;
    return new Response(JSON.stringify(payload), {
      headers: { 'content-type': 'application/json' },
    });
  };
  return { fetcher, calls };
}

describe('PlanEntryGateway', () => {
  it('sends the exact method, path, and body for API-001/002/003/004/025', async () => {
    const { fetcher, calls } = createFetcher(Array.from({ length: 5 }, () => successEnvelope));
    const gateway = createPlanEntryGateway(fetcher);
    const syncInput: Api003Request = { scenarioId: 'SCN-01' };
    const confirmInput: Api004Request = {
      reason: 'verified',
      supplements: { source: 'manual' },
    };
    const resetInput: Api025Request = { scenarioId: 'SCN-02' };

    await gateway.getOverview();
    await gateway.getPlans();
    await gateway.syncPlans(syncInput);
    await gateway.confirmPlan('PLAN-001', confirmInput);
    await gateway.resetDemo(resetInput);

    expect(calls).toEqual([
      { input: '/mock/overview', init: undefined },
      { input: '/mock/plans', init: undefined },
      {
        input: '/mock/plans/sync',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(syncInput),
        },
      },
      {
        input: '/mock/plans/PLAN-001/confirm',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(confirmInput),
        },
      },
      {
        input: '/mock/demo/reset',
        init: {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(resetInput),
        },
      },
    ]);
  });

  it('strictly parses success and failure envelopes', async () => {
    const { fetcher } = createFetcher([successEnvelope, errorEnvelope]);
    const gateway = createPlanEntryGateway(fetcher);

    await expect(gateway.getOverview()).resolves.toEqual(successEnvelope);
    await expect(gateway.getPlans()).resolves.toEqual(errorEnvelope);
  });

  it('rejects a malformed envelope', async () => {
    const { fetcher } = createFetcher([
      { ok: true, data: {}, auditLogId: 'AUDIT-003', traceId: 'TRACE-003', extra: true },
    ]);
    const gateway = createPlanEntryGateway(fetcher);

    await expect(gateway.getPlans()).rejects.toBeInstanceOf(ZodError);
  });
});
