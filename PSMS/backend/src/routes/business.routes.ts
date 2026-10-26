import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { isBusinessCollection, readBusinessPage } from '../services/business-read.service.js';

export const businessRouter = Router();
businessRouter.get('/:collection', authenticate, async (req, res, next) => {
  const collection = String(req.params.collection);
  if (!isBusinessCollection(collection)) { res.status(404).json({ ok: false, message: '业务列表不存在' }); return; }
  try {
    res.setHeader('Cache-Control', 'no-store');
    res.json({ ok: true, data: await readBusinessPage(collection, req.user!.dataScope, req.query) });
  } catch (error) { next(error); }
});
