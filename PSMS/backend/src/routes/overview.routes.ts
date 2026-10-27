import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { readBusinessOverview } from '../services/overview.service.js';

export const overviewRouter = Router();
overviewRouter.get('/', authenticate, async (req, res, next) => {
  try {
    const data = await readBusinessOverview(req.user!.dataScope, req.query.workArea, req.query.date);
    res.setHeader('Cache-Control', 'no-store');
    res.json({ ok: true, data });
  } catch (error) { next(error); }
});
