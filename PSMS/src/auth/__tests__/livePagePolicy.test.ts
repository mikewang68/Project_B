import {describe,it,expect}from'vitest';
import{connectedLivePageIds,livePageAllowed}from'../livePagePolicy';
const actor={actorId:'a',roleCode:'DISPATCHER' as const,dataScope:['AREA-A'],online:true,demoTime:'now',
 permissions:['pps:api:plans:read','pps:api:plans:write']};
describe('native live menu permission boundary',()=>{
 it('does not infer all dispatcher pages from a plan-only business role',()=>{
  expect(livePageAllowed(actor,'UI-002','plan:view')).toBe(true);
  for(const id of ['UI-005','UI-006','UI-007','UI-008'])expect(livePageAllowed(actor,id,'dispatch:view')).toBe(false);
 });
 it('enables only connected authorized pages and keeps the readonly veto over admin flags',()=>{
  expect(connectedLivePageIds).toContain('UI-005');expect(connectedLivePageIds).toContain('UI-009');
  expect(connectedLivePageIds).not.toContain('UI-003');
  expect(livePageAllowed({...actor,fullAdmin:true,readOnly:true},'UI-005','dispatch:view')).toBe(false);
  expect(livePageAllowed({...actor,fullAdmin:true,readOnly:true,permissions:['pps:api:work-orders:read']},'UI-005','dispatch:view')).toBe(true);
 });
 it('does not replace native read requirements with an unrelated view grant',()=>{
  expect(livePageAllowed({...actor,permissions:['dispatch:view']},'UI-005','dispatch:view')).toBe(false);
 });
});
