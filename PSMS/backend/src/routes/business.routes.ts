import { Router } from 'express';
import { authenticate } from '../middleware/auth.js';
import { isBusinessCollection, readBusinessPage } from '../services/business-read.service.js';
import { executeBusinessCommand,createBusinessWorkOrder } from '../services/business-command.service.js';
import { confirmWorkOrder, createAiWorkOrderDraft, patchWorkOrder } from '../services/workOrderAi.service.js';
import { env } from '../config/env.js';

export const businessRouter = Router();

/** 工单 AI 草稿/编辑/确认都必须有当前统一身份会话；本地角色在 service 里再校验。 */
function requirePlatformSubject(res: import('express').Response): string | null {
  return typeof res.locals.platformSubject === 'string' ? res.locals.platformSubject : null;
}

businessRouter.post('/work-orders/ai-draft', authenticate, async (req, res, next) => {
  const subject = requirePlatformSubject(res);
  if (!env.PSMS_SSO_ENABLED || !subject) { res.status(403).json({ ok: false, message: '此操作需要当前统一身份会话' }); return; }
  try {
    const data = await createAiWorkOrderDraft(req.body, { ...req.user!, subject });
    res.status(201).json({ ok: true, data });
  } catch (error) { next(error); }
});

businessRouter.patch('/work-orders/:id', authenticate, async (req, res, next) => {
  const subject = requirePlatformSubject(res);
  if (!env.PSMS_SSO_ENABLED || !subject) { res.status(403).json({ ok: false, message: '此操作需要当前统一身份会话' }); return; }
  try {
    const data = await patchWorkOrder(String(req.params.id), req.body, { ...req.user!, subject });
    res.json({ ok: true, data });
  } catch (error) { next(error); }
});

businessRouter.post('/work-orders/:id/confirm', authenticate, async (req, res, next) => {
  const subject = requirePlatformSubject(res);
  if (!env.PSMS_SSO_ENABLED || !subject) { res.status(403).json({ ok: false, message: '此操作需要当前统一身份会话' }); return; }
  try {
    const data = await confirmWorkOrder(String(req.params.id), req.body, { ...req.user!, subject });
    res.json({ ok: true, data });
  } catch (error) { next(error); }
});
businessRouter.post('/work-orders',authenticate,async(req,res,next)=>{
  if(!env.PSMS_SSO_ENABLED||typeof res.locals.platformSubject!=='string'){
    res.status(403).json({ok:false,message:'此操作需要当前统一身份会话'});return;
  }
  try{res.status(201).json({ok:true,data:await createBusinessWorkOrder(req.body,{...req.user!,subject:res.locals.platformSubject})});}
  catch(error){next(error);}
});
businessRouter.get('/:collection', authenticate, async (req, res, next) => {
  const collection = String(req.params.collection);
  if (!isBusinessCollection(collection)) { res.status(404).json({ ok: false, message: '业务列表不存在' }); return; }
  try {
    res.setHeader('Cache-Control', 'no-store');
    res.json({ ok: true, data: await readBusinessPage(collection, req.user!.dataScope, req.query) });
  } catch (error) { next(error); }
});
businessRouter.post('/:collection/:id/actions',authenticate,async(req,res,next)=>{
  const collection=String(req.params.collection);
  if(!isBusinessCollection(collection)){res.status(404).json({ok:false,message:'业务对象不存在'});return;}
  // These commands require current central authorization and explicit subject mapping.
  if(!env.PSMS_SSO_ENABLED||typeof res.locals.platformSubject!=='string'){
    res.status(403).json({ok:false,message:'此操作需要当前统一身份会话'});return;
  }
  try{res.json({ok:true,data:await executeBusinessCommand(collection,String(req.params.id),req.body,
    {...req.user!,subject:res.locals.platformSubject})});}catch(error){next(error);}
});
