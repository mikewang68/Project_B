import {describe,expect,it} from 'vitest';
import {centralActionAllowed,centralPermissionAllowed} from '../centralActions';
import {livePageAllowed} from '../livePagePolicy';
import {canRunBusinessAction} from '../../real/business';
import type {SessionContext} from '../types';
const current=(operations:Record<string,string[]>,readOnly=false):SessionContext=>({
 actorId:'central-operator',roleCode:readOnly?'AUDITOR':'DISPATCHER',dataScope:['AREA-A'],
 online:true,demoTime:'2026-11-02T00:00:00Z',readOnly,fullAdmin:true,permissions:['pps:export'],
 menuOperations:operations
});
describe('PSMS UI follows the current menu action ceiling',()=>{
 it('does not allow the legacy admin flag to bypass missing central menus',()=>{
  const session=current({'PSMS:ui-002':['VIEW']});
  expect(livePageAllowed(session,'UI-002','plan:view')).toBe(true);
  expect(livePageAllowed(session,'UI-013','audit:view')).toBe(false);
  expect(centralPermissionAllowed(session,'plan:confirm')).toBe(false);
  expect(centralPermissionAllowed(session,'demo:reset')).toBe(false);
 });
 it('allows readonly EXPORT only on the granted report menu',()=>{
  const session=current({'PSMS:ui-011':['VIEW','EXPORT','ADD']},true);
  expect(centralPermissionAllowed(session,'report:export')).toBe(true);
  expect(centralActionAllowed(session,'PSMS:ui-011','ADD')).toBe(false);
  expect(centralActionAllowed(session,'PSMS:ui-013','EXPORT')).toBe(false);
  expect(centralPermissionAllowed({...session,menuOperations:{'PSMS:ui-011':['VIEW']}},'report:export')).toBe(false);
 });
 it('keeps job creation, execution and completion separate',()=>{
  const session=current({'PSMS:ui-005':['VIEW','ADD']});
  expect(canRunBusinessAction(session,{code:'create',label:'create',permission:'pps:api:work-orders:write'})).toBe(true);
  expect(canRunBusinessAction(session,{code:'assign',label:'assign',permission:'pps:api:work-orders:write'})).toBe(false);
  expect(canRunBusinessAction(session,{code:'complete',label:'complete',permission:'pps:api:work-orders:execute'})).toBe(false);
  expect(canRunBusinessAction(current({'PSMS:ui-005':['CONFIRM']}),
   {code:'complete',label:'complete',permission:'pps:api:work-orders:execute'})).toBe(true);
 });
});
