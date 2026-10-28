import { describe,expect,it } from 'vitest';
import { mayWriteBusinessRoute } from '../src/middleware/sso-policy.js';
const planner={readOnly:false,superAdmin:false,permissions:['pps:api:plans:write']};
describe('current SSO operation grants',()=>{
 it('allows a verified local application admin but preserves the readonly veto',()=>{
  const admin={readOnly:false,superAdmin:false,applicationAdmin:true,permissions:[]};
  expect(mayWriteBusinessRoute(admin,'/api/plans/P1/execute')).toBe(true);
  expect(mayWriteBusinessRoute({...admin,readOnly:true},'/api/plans/P1/execute')).toBe(false);
 });
 it('permits plan entry without granting approval or execution',()=>{
  expect(mayWriteBusinessRoute(planner,'/api/plans/import')).toBe(true);
  expect(mayWriteBusinessRoute(planner,'/api/plans/P1/confirm')).toBe(false);
  expect(mayWriteBusinessRoute(planner,'/api/plans/P1/execute')).toBe(false);
  expect(mayWriteBusinessRoute(planner,'/api/settings')).toBe(false);
 });
 it('checks approval separately',()=>expect(mayWriteBusinessRoute({...planner,permissions:['pps:api:plans:approve']},'/api/plans/P1/confirm')).toBe(true));
 it('readonly veto survives a conflicting administrator flag',()=>expect(mayWriteBusinessRoute({...planner,superAdmin:true,readOnly:true},'/api/plans/import')).toBe(false));
});
