import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, rmdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Server } from 'node:http';
import express from 'express';

const fake = vi.hoisted(() => ({
  env: { PSMS_SSO_ENABLED: true, PSMS_IAM_BASE_URL: 'http://iam.example.test',
    PSMS_SSO_SERVICE_TOKEN: 'test-only-service-credential-value', PSMS_SSO_BINDINGS_FILE: '', PSMS_COOKIE_SECURE: true },
  user: { _id: 'local-user', username: 'scoped-reader', actorId: 'local-actor', roleCode: 'viewer', dataScope: ['AREA-A'] },
}));
vi.mock('../src/config/env.js', () => ({ env: fake.env }));
vi.mock('../src/db/tables.js', () => ({ User: { findById: () => ({ lean: async () => ({ ...fake.user }) }) } }));

const subject = '123456781234123412341234567890ab';
const actualFetch = globalThis.fetch;
let directory: string;
let server: Server;
let base: string;
let calls: number;
let online: boolean;
let current: { subject: string; sessionId: string; localSubject: string; audience: string;
  readOnly: boolean; superAdmin: boolean; permissions: string[] };

beforeAll(async () => {
  directory = mkdtempSync(join(tmpdir(), 'psms-sso-authorization-'));
  fake.env.PSMS_SSO_BINDINGS_FILE = join(directory, 'bindings.json');
  writeFileSync(fake.env.PSMS_SSO_BINDINGS_FILE, JSON.stringify({ [subject]: { userId: fake.user._id, enabled: true } }));
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code: 0, data: current }), {
    status: online ? 200 : 503, headers: { 'Content-Type': 'application/json' },
  })));
  const { ssoRouter, ssoGuard } = await import('../src/middleware/sso.js');
  const app = express();
  app.use(express.json());
  app.use('/api/auth/sso', ssoRouter);
  app.use(ssoGuard);
  app.get(['/api/plans', '/api/work-orders'], (_req, res) => { calls++; res.json({ ok: true }); });
  app.post('/api/plans/import', (_req, res) => { calls++; res.json({ ok: true }); });
  server = app.listen(0, '127.0.0.1');
  await new Promise<void>(resolve => server.once('listening', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Test listener has no port');
  base = `http://127.0.0.1:${address.port}`;
});

beforeEach(() => {
  calls = 0; online = true;
  fake.user.roleCode = 'viewer'; fake.user.dataScope = ['AREA-A'];
  current = { subject, sessionId: `test-session-${crypto.randomUUID()}`, localSubject: fake.user.username,
    audience: 'psms', readOnly: true, superAdmin: false, permissions: ['plan:view', 'overview:view'] };
});

afterAll(async () => {
  vi.unstubAllGlobals();
  if (server) await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  if (directory) { unlinkSync(join(directory, 'bindings.json')); rmdirSync(directory); }
});

async function exchange() {
  const response = await actualFetch(`${base}/api/auth/sso/exchange`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ticket: 'test-only-one-use-ticket-value' }),
  });
  expect(response.status).toBe(200);
  const cookie = response.headers.get('set-cookie')!.split(';')[0];
  const body = await response.json() as { data: { csrf: string } };
  return { cookie, csrf: body.data.csrf };
}

describe('native SSO session uses current authorization on every request', () => {
  it('removes and restores a plan grant without replacing the native cookie', async () => {
    const { cookie } = await exchange();
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(200);
    current.permissions = ['overview:view'];
    const rejected = await actualFetch(`${base}/api/plans`, { headers: { cookie } });
    expect(rejected.status).toBe(403);
    expect((await rejected.json()).errorCode).toBe('READ_PERMISSION_DENIED');
    expect(calls).toBe(1);
    current.permissions.push('plan:view');
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(200);
    expect(calls).toBe(2);
  });

  it('does not treat a global native data scope as permission to read other resources', async () => {
    fake.user.dataScope = ['*'];
    const { cookie } = await exchange();
    expect((await actualFetch(`${base}/api/work-orders`, { headers: { cookie } })).status).toBe(403);
    expect(calls).toBe(0);
  });

  it('intersects central administration with the current native role', async () => {
    fake.user.dataScope = ['*']; current.readOnly = false; current.superAdmin = true; current.permissions = [];
    const { cookie } = await exchange();
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(403);
    fake.user.roleCode = 'admin';
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(200);
    expect(calls).toBe(1);
  });

  it('applies a readonly downgrade to an existing administrator cookie even with valid CSRF', async () => {
    fake.user.roleCode = 'admin'; current.readOnly = false; current.superAdmin = true;
    const { cookie, csrf } = await exchange();
    const options = { method: 'POST', headers: { cookie, 'X-PSMS-CSRF': csrf } };
    expect((await actualFetch(`${base}/api/plans/import`, options)).status).toBe(200);
    current.readOnly = true;
    expect((await actualFetch(`${base}/api/plans/import`, options)).status).toBe(403);
    expect(calls).toBe(1);
  });

  it('fails closed when the current IAM session cannot be checked', async () => {
    const { cookie } = await exchange(); online = false;
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(503);
    expect(calls).toBe(0);
  });

  it('rejects a changed identity assertion rather than reusing the earlier cookie claims', async () => {
    const { cookie } = await exchange(); current.localSubject = 'other-user';
    expect((await actualFetch(`${base}/api/plans`, { headers: { cookie } })).status).toBe(401);
    expect(calls).toBe(0);
  });
});
