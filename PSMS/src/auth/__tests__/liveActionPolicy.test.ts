import { describe,expect,it } from 'vitest';
import { canLiveMenuAction } from '../liveActionPolicy';
import { livePageAllowed } from '../livePagePolicy';
import { businessActions,canReadCollection,canRunBusinessAction } from '../../real/business';
import type { SessionContext } from '../types';
const actor:SessionContext={actorId:'central-operator',roleCode:'DISPATCHER',dataScope:['AREA-A'],online:true,demoTime:'now',permissions:[],
  menuOperations:{'PSMS:ui-005':['VIEW','ADD','EDIT']}};
describe('central live action policy',()=>{
  it('supports central actions without legacy permission codes and separates confirmation',()=>{
    expect(livePageAllowed(actor,'UI-005','dispatch:view')).toBe(true);
    expect(canReadCollection(actor,'work-orders')).toBe(true);
    expect(canLiveMenuAction(actor,'PSMS:ui-005','ADD','pps:api:work-orders:write')).toBe(true);
    expect(canRunBusinessAction({...actor,menuOperations:{'PSMS:ui-005':['VIEW','ADD']}},{code:'create',label:'新建工单',permission:'pps:api:work-orders:write'})).toBe(true);
    expect(canRunBusinessAction({...actor,menuOperations:{'PSMS:ui-005':['VIEW','EDIT']}},{code:'create',label:'新建工单',permission:'pps:api:work-orders:write'})).toBe(false);
    expect(canRunBusinessAction(actor,businessActions('work-orders','ASSIGNED')[0])).toBe(true);
    expect(canRunBusinessAction(actor,businessActions('work-orders','IN_PROGRESS').find(a=>a.code==='complete')!)).toBe(false);
    expect(canLiveMenuAction(actor,'PSMS:ui-005','CONFIRM','pps:api:work-orders:execute')).toBe(false);
  });
  it('never lets fullAdmin or old permissions bypass a present central denial or readonly',()=>{
    const admin={...actor,fullAdmin:true,permissions:['pps:api:work-orders:execute'],menuOperations:{}};
    expect(canLiveMenuAction(admin,'PSMS:ui-005','EDIT','pps:api:work-orders:execute')).toBe(false);
    expect(canReadCollection(admin,'work-orders')).toBe(false);
    expect(canLiveMenuAction({...actor,readOnly:true},'PSMS:ui-005','ADD','pps:api:work-orders:write')).toBe(false);
    expect(canReadCollection({...actor,readOnly:true},'work-orders')).toBe(true);
  });
});
