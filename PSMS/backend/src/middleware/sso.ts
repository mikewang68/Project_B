import { readFileSync } from 'node:fs';
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto';
import { Router, type Request, type Response, type NextFunction } from 'express';
import { z } from 'zod';
import { User, type UserDoc } from '../db/tables.js';
import { env } from '../config/env.js';
import { mayReadBusinessRoute } from './sso-policy.js';

const bindingsSchema = z.record(z.string().regex(/^(?:[a-f\d]{32}|[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12})$/i),
  z.object({ userId: z.string().min(1), enabled: z.boolean() }).strict());
const assertionSchema = z.object({ subject: z.string(), sessionId: z.string().min(1), localSubject: z.string().min(1),
  audience: z.union([z.literal('psms'), z.literal('[psms]'), z.tuple([z.literal('psms')])]),
  readOnly:z.boolean(),superAdmin:z.boolean(),permissions:z.array(z.string()),
  company: z.string().nullish(), org: z.string().nullish(), domain: z.string().nullish(), zone: z.string().nullish() });
type Session = { userId: string; subject: string; iamSid: string; csrf: string; expires: number };
const sessions = new Map<string, Session>();
const revoked = new Map<string, number>();
const TTL = 8 * 3600_000;
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const loopback = (req: Request) => ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress ?? '');
const fail = (res: Response, status: number, code: string) => { res.status(status).json({ ok: false, errorCode: code, message: code }); };
const enabled = env.PSMS_SSO_ENABLED;
const bindings = enabled ? bindingsSchema.parse(JSON.parse(readFileSync(env.PSMS_SSO_BINDINGS_FILE, 'utf8'))) : {};
if (enabled) {
  const url = new URL(env.PSMS_IAM_BASE_URL);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash
      || env.PSMS_SSO_SERVICE_TOKEN.length < 24 || !Object.keys(bindings).length) throw new Error('SSO configuration is incomplete');
}
function sweep() {
  const now = Date.now();
  for (const [key, value] of sessions) if (value.expires <= now) sessions.delete(key);
  for (const [key, until] of revoked) if (until <= now) revoked.delete(key);
}
function service(req: Request) {
  if (!loopback(req)) return false;
  const actual = Buffer.from(req.header('X-SSO-Service-Token') ?? '');
  const expected = Buffer.from(env.PSMS_SSO_SERVICE_TOKEN);
  return expected.length >= 24 && actual.length === expected.length && timingSafeEqual(actual, expected);
}
function usable(user: UserDoc | null): user is UserDoc {
  return !!user && ['super_admin', 'admin', 'scheduler', 'dispatcher', 'operator', 'viewer'].includes(user.roleCode)
    && Array.isArray(user.dataScope) && user.dataScope.length > 0
    && user.dataScope.every((scope) => typeof scope === 'string' && scope.length > 0);
}

export const ssoRouter = Router();
function localSession(req:Request):Session|undefined{
  const value=req.headers.cookie?.split(';').map(v=>v.trim()).find(v=>v.startsWith('PSMS_SSO='))?.slice(9);
  return value&&/^[\w-]{43}$/.test(value)?sessions.get(digest(value)):undefined;
}
async function iamSession(session:Session,action='introspect'){
  return fetch(`${env.PSMS_IAM_BASE_URL.replace(/\/$/,'')}/api/v1/iam/auth/sso/session/${action}`,{
    method:'POST',redirect:'error',signal:AbortSignal.timeout(5000),
    headers:{'Content-Type':'application/json','X-SSO-Service-Token':env.PSMS_SSO_SERVICE_TOKEN},
    body:JSON.stringify({subject:session.subject,sessionId:session.iamSid,audience:'psms'}),
  });
}
ssoRouter.get('/csrf',async(req,res,next)=>{
  const session=localSession(req);if(!session){fail(res,401,'UNAUTHORIZED');return;}
  try{const response=await iamSession(session);if(!response.ok){fail(res,401,'UNAUTHORIZED');return;}
    res.setHeader('Cache-Control','no-store');res.json({ok:true,data:{csrf:session.csrf}});
  }catch(error){next(error);}
});
ssoRouter.post('/logout',async(req,res,next)=>{
  const session=localSession(req);if(!session){fail(res,401,'UNAUTHORIZED');return;}
  if(req.header('X-PSMS-CSRF')!==session.csrf){fail(res,403,'CSRF_REJECTED');return;}
  try{const response=await iamSession(session,'logout');if(!response.ok){fail(res,502,'SSO_LOGOUT_UNAVAILABLE');return;}
    for(const [key,value] of sessions)if(value.iamSid===session.iamSid)sessions.delete(key);
    res.setHeader('Set-Cookie',`PSMS_SSO=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0${env.PSMS_COOKIE_SECURE?'; Secure':''}`);
    res.json({ok:true});
  }catch(error){next(error);}
});
ssoRouter.post('/exchange', async (req, res, next) => {
  if (!enabled) { fail(res, 503, 'SSO_DISABLED'); return; }
  // The gateway is loopback-only. Never trust browser claims or forwarded client addresses.
  if (!loopback(req)) { fail(res, 403, 'SSO_PEER_DENIED'); return; }
  if (typeof req.body?.ticket !== 'string' || req.body.ticket.length < 20 || req.body.ticket.length > 8192) {
    fail(res, 400, 'SSO_TICKET_REQUIRED'); return;
  }
  try {
    sweep();
    const response = await fetch(`${env.PSMS_IAM_BASE_URL.replace(/\/$/, '')}/api/v1/iam/auth/sso/verify-and-consume`, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
      headers: { 'Content-Type': 'application/json', 'X-SSO-Service-Token': env.PSMS_SSO_SERVICE_TOKEN },
      body: JSON.stringify({ ticket: req.body.ticket, audience: 'psms' }),
    });
    if (!response.ok) { fail(res, 401, 'SSO_TICKET_REJECTED'); return; }
    const result = await response.json() as { code?: unknown; data?: unknown };
    if (result.code !== 0) { fail(res, 401, 'SSO_TICKET_REJECTED'); return; }
    const assertion = assertionSchema.safeParse(result.data);
    if (!assertion.success) { fail(res, 403, 'SSO_BAD_ASSERTION'); return; }
    const a = assertion.data;
    // There is no company/domain/zone model in PSMS. Do not silently widen unsupported scopes.
    if ([a.company, a.org, a.domain, a.zone].some((value) => value?.trim())) { fail(res, 403, 'SSO_SCOPE_UNSUPPORTED'); return; }
    const binding = bindings[a.subject];
    if (!binding?.enabled) { fail(res, 403, 'SSO_NO_LOCAL_MAPPING'); return; }
    const user = await User.findById(binding.userId).lean<UserDoc>();
    if (!usable(user) || user.username !== a.localSubject || revoked.has(a.sessionId)) { fail(res, 403, 'SSO_LOCAL_IDENTITY_REJECTED'); return; }
    if (sessions.size >= 10000 || revoked.size >= 10000) { fail(res, 503, 'SSO_CAPACITY'); return; }
    const cookie = randomBytes(32).toString('base64url'); const csrf = randomBytes(32).toString('base64url');
    const old = req.headers.cookie?.split(';').map((v) => v.trim()).find((v) => v.startsWith('PSMS_SSO='))?.slice(9);
    if (old) sessions.delete(digest(old));
    sessions.set(digest(cookie), { userId: user._id, subject: a.subject, iamSid: a.sessionId, csrf, expires: Date.now() + TTL });
    res.setHeader('Set-Cookie', `PSMS_SSO=${cookie}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${TTL / 1000}${env.PSMS_COOKIE_SECURE ? '; Secure' : ''}`);
    res.setHeader('Cache-Control', 'no-store'); res.json({ ok: true, data: { csrf } });
  } catch (error) {
    // Network/JSON errors fail closed; do not log a ticket, service credential or session.
    if (error instanceof TypeError || error instanceof SyntaxError || (error as Error).name === 'TimeoutError') {
      fail(res, 502, 'SSO_IAM_UNAVAILABLE'); return;
    }
    next(error);
  }
});
ssoRouter.post('/revoke', (req, res) => {
  if (!enabled || !service(req)) { fail(res, 403, 'SSO_SERVICE_DENIED'); return; }
  const sid = req.body?.iamSessionId;
  if (typeof sid !== 'string' || !sid || sid.length > 256) { fail(res, 400, 'SSO_BAD_SESSION'); return; }
  sweep();
  // Do not evict an unexpired revocation. Failing capacity is safer than accepting a revoked SID.
  if (revoked.size >= 10000 && !revoked.has(sid)) { fail(res, 503, 'SSO_CAPACITY'); return; }
  revoked.set(sid, Date.now() + TTL); let count = 0;
  for (const [key, session] of sessions) if (session.iamSid === sid) { sessions.delete(key); count++; }
  res.json({ ok: true, data: { revoked: count } });
});

export async function ssoGuard(req: Request, res: Response, next: NextFunction): Promise<void> {
  if (!enabled || !req.path.startsWith('/api/')) { next(); return; }
  sweep();
  const cookie = req.headers.cookie?.split(';').map((v) => v.trim()).find((v) => v.startsWith('PSMS_SSO='))?.slice(9);
  const session = cookie && /^[\w-]{43}$/.test(cookie) ? sessions.get(digest(cookie)) : undefined;
  if (!session || revoked.has(session.iamSid)) { fail(res, 401, 'UNAUTHORIZED'); return; }
  try {
    const binding = bindings[session.subject];
    const user = await User.findById(session.userId).lean<UserDoc>();
    if (!binding?.enabled || binding.userId !== session.userId || !usable(user)) { fail(res, 401, 'SSO_LOCAL_IDENTITY_REJECTED'); return; }
    const response=await iamSession(session);
    if(!response.ok){fail(res,response.status===401||response.status===403?401:503,'SSO_SESSION_UNAVAILABLE');return;}
    const result=await response.json() as {code?:unknown;data?:unknown};
    const assertion=assertionSchema.safeParse(result.data);
    if(result.code!==0||!assertion.success||assertion.data.subject!==session.subject||assertion.data.sessionId!==session.iamSid
        ||assertion.data.localSubject!==user.username){fail(res,401,'SSO_LOCAL_IDENTITY_REJECTED');return;}
    const current=assertion.data;
    const effective = {readOnly: current.readOnly || user.roleCode === 'viewer',
      superAdmin: current.superAdmin && ['super_admin','admin'].includes(user.roleCode), permissions: current.permissions};
    res.locals.platformAuthorization = effective;
    if([current.company,current.org,current.domain,current.zone].some(value=>value?.trim())){fail(res,403,'SSO_SCOPE_UNSUPPORTED');return;}
    // Scope limits form a ceiling; current business grants are still required below.
    // Restricted scopes use only the plan adapter until other row-level adapters exist.
    const all=user.dataScope.some(scope=>['*','GLOBAL'].includes(scope));
    if (!all && req.path !== '/api/auth/me' && req.path !== '/api/plans' && !req.path.startsWith('/api/plans/')) {
      fail(res, 403, 'SSO_ROUTE_NOT_ADAPTED'); return;
    }
    req.user = { userId: user._id, actorId: user.actorId, roleCode: user.roleCode, dataScope: [...user.dataScope] };
    if (['GET', 'HEAD'].includes(req.method) && !mayReadBusinessRoute(effective, req.path)) {
      fail(res, 403, 'READ_PERMISSION_DENIED'); return;
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method)) {
      if (effective.readOnly) { fail(res, 403, 'READ_ONLY'); return; }
      if(!effective.superAdmin&&!current.permissions.some(permission=>permission===`pps:api:${req.path.replace(/^\/api\//,'').split('/')[0]}:write`)){
        fail(res,403,'OPERATION_PERMISSION_DENIED');return;
      }
      if (req.header('X-PSMS-CSRF') !== session.csrf) { fail(res, 403, 'CSRF_REJECTED'); return; }
    }
    if(req.path.includes('/export')&&!(effective.superAdmin&&!effective.readOnly)&&!current.permissions.includes('pps:export')){fail(res,403,'EXPORT_PERMISSION_DENIED');return;}
    next();
  } catch (error) { next(error); }
}
