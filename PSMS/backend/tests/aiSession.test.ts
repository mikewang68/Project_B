import { describe, expect, it, vi } from 'vitest';
import jwt from 'jsonwebtoken';
const secret = vi.hoisted(() => 'test-ai-session-secret-only');
vi.mock('../src/config/env.js', () => ({ env: { JWT_SECRET: secret } }));
import { authenticate, optionalAuth } from '../src/middleware/auth.js';

describe('AI demo session isolation', () => {
  const token = () => jwt.sign({ userId: 'AI-DEMO', actorId: 'AI-DEMO', roleCode: 'viewer', dataScope: ['AREA-A'], purpose: 'ai-demo' }, secret);
  it('allows its AI endpoint but rejects business endpoints', () => {
    for (const baseUrl of ['/api/ai', '/api/plans', '/api/auth']) {
      const req: any = { headers: { authorization: `Bearer ${token()}` }, baseUrl };
      const res: any = { status: vi.fn().mockReturnThis(), json: vi.fn() };
      const next = vi.fn(); authenticate(req, res, next);
      expect(next).toHaveBeenCalledTimes(baseUrl === '/api/ai' ? 1 : 0);
      if (baseUrl !== '/api/ai') expect(res.status).toHaveBeenCalledWith(403);
    }
  });
  it('does not treat an AI demo session as a business read identity', () => {
    const req: any = { headers: { authorization: `Bearer ${token()}` } };
    const next = vi.fn(); optionalAuth(req, {} as any, next);
    expect(req.user).toBeUndefined(); expect(next).toHaveBeenCalledOnce();
  });
});
