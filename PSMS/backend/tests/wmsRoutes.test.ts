import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import express from 'express';
import type { Server } from 'node:http';
import { errorHandler } from '../src/middleware/errorHandler.js';

const mock = vi.hoisted(() => ({ receive: vi.fn(), detail: vi.fn() }));
vi.mock('../src/services/wms-inbound.service.js', () => ({
  WmsInbound: class { config = { receiveToken: 'r'.repeat(32) }; receive = mock.receive; detail = mock.detail; },
}));
let server: Server, base: string;
beforeAll(async () => {
  vi.stubEnv('PSMS_WMS_ENABLED', 'true');
  vi.stubEnv('PSMS_WMS_DATABASE', 'psms_wms_joint_test');
  const { wmsEventRouter } = await import('../src/routes/wms-inbound.routes.js');
  const app = express();
  app.use(express.json());
  app.use('/api/integrations/wms', wmsEventRouter);
  // Browser-only routes still require their native login after this narrow router.
  app.use((_req, res) => { res.status(401).json({ code: 'BROWSER_LOGIN_REQUIRED' }); });
  app.use(errorHandler);
  server = app.listen(0, '127.0.0.1');
  await new Promise<void>(resolve => server.once('listening', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No test listener');
  base = 'http://127.0.0.1:' + address.port;
});
afterAll(async () => { vi.unstubAllEnvs(); await new Promise<void>(resolve => server.close(() => resolve())); });
describe('dedicated WMS callback identity', () => {
  it('rejects missing and wrong service credentials without calling business logic', async () => {
    for (const token of ['', 'Bearer invalid']) {
      const response = await fetch(base + '/api/integrations/wms/inbound-events', { method: 'POST', headers: { Authorization: token } });
      expect(response.status).toBe(401);
      expect((await response.json()).code).toBe('UNAUTHORIZED');
    }
    expect(mock.receive).not.toHaveBeenCalled();
  });
  it('accepts a correctly authenticated callback without a browser session', async () => {
    mock.receive.mockResolvedValue({ accepted: true, eventId: 'E1' });
    const response = await fetch(base + '/api/integrations/wms/inbound-events', { method: 'POST',
      headers: { Authorization: 'Bearer ' + 'r'.repeat(32), 'Content-Type': 'application/json' }, body: JSON.stringify({ eventId: 'E1' }) });
    expect(response.status).toBe(200);
    expect(mock.receive).toHaveBeenCalledWith({ eventId: 'E1' });
  });
  it('returns the agreed 400 shape for malformed callback JSON', async () => {
    const response = await fetch(base + '/api/integrations/wms/inbound-events', { method: 'POST',
      headers: { Authorization: 'Bearer ' + 'r'.repeat(32), 'Content-Type': 'application/json' }, body: '{' });
    expect(response.status).toBe(400);
    expect((await response.json()).code).toBe('INVALID_JSON');
  });
  it('does not extend the callback identity to browser business routes or other methods', async () => {
    const headers = { Authorization: 'Bearer ' + 'r'.repeat(32) };
    expect((await fetch(base + '/api/business/work-orders', { headers })).status).toBe(401);
    expect((await fetch(base + '/api/integrations/wms/inbound-events', { headers })).status).toBe(401);
  });
});
