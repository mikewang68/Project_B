import { beforeAll, afterAll, afterEach, describe, expect, it, vi } from 'vitest';
import { PGlite } from '@electric-sql/pglite';
import type { PoolClient } from 'pg';
import { SCHEMA_STATEMENTS } from '../src/db/schema.js';
import { RailwayRuntime, generateRailwayTicket, railwayTicketSchema, shanghaiDate } from '../src/services/railway-runtime.service.js';
import { dailyRailwayJob, railwayScheduleSettings, startRailwayWorker } from '../src/services/railway-scheduler.service.js';
vi.mock('../src/db/openGaussClient.js', () => ({ connectClient: vi.fn(() => {
  throw new Error('This test must use its explicit isolated database connection');
}) }));
afterEach(()=>vi.useRealTimers());

describe('railway durable PostgreSQL workflow',()=>{
  let db:PGlite,runtime:RailwayRuntime,connect:()=>Promise<Pick<PoolClient,'query'|'release'>>;
  beforeAll(async()=>{
    db=await PGlite.create();for(const sql of SCHEMA_STATEMENTS){
      const add=sql.match(/^ALTER TABLE (\w+) ADD COLUMN (\w+)/);
      if(add && (await db.query('SELECT 1 FROM information_schema.columns WHERE table_schema=$1 AND table_name=$2 AND column_name=$3',['public',add[1],add[2]])).rows.length)continue;
      await db.exec(sql);
    }
    connect=async()=>({query:(sql:string,values?:unknown[])=>db.query(sql,values),release:()=>{}} as unknown as Pick<PoolClient,'query'|'release'>);
    runtime=new RailwayRuntime(connect,0,true);await runtime.initialize();
  },30000);
  afterAll(async()=>{await db?.close();});
  it('generates different daily orders, rejects real-device orders, uses Beijing dates',()=>{
    const a=generateRailwayTicket('2026-10-29'),b=generateRailwayTicket('2026-10-30');
    expect(a).not.toEqual(b);expect(a).toEqual(generateRailwayTicket(a.date));
    expect(railwayTicketSchema.safeParse({...a,simulated:false}).success).toBe(false);
    expect(railwayTicketSchema.safeParse({...a,id:'SIM-RAIL-20261030'}).success).toBe(false);
    expect(shanghaiDate(new Date('2026-10-28T17:00:00Z'))).toBe('2026-10-29');
  });
  it('persists 16 ordered transitions, native business records and audit; duplicate import is idempotent',async()=>{
    const ticket=generateRailwayTicket('2026-10-29');
    await runtime.importTicket(ticket,'TEST-USER');await runtime.importTicket(ticket,'TEST-USER');
    for(let n=0;n<7;n++)await runtime.tick();
    // Simulate a new worker instance connecting to the persisted database.
    const resumed=new RailwayRuntime(connect,0,true);
    for(let n=7;n<16;n++)await resumed.tick();
    expect(await runtime.tick()).toBeNull();
    const snapshot=await runtime.overview();expect(snapshot.runs).toHaveLength(1);expect(snapshot.runs[0].status).toBe('COMPLETED');
    expect(snapshot.events).toHaveLength(16);
    expect(snapshot.source.kind).toBe('LOCAL_DEMO');
    expect(snapshot.orders[0]).toMatchObject({id:ticket.id+'-WO',business_date:'2026-10-29',status:'COMPLETED'});
    const detail=await runtime.detail(ticket.id);
    expect(detail.ticket.payload.details).toEqual(ticket.details);
    expect(detail.workOrder?.dispatch_details).toEqual(ticket.details!.dispatch);
    expect(detail.workOrder?.assigned_operator).toBe(ticket.details!.resources.operatorCode);
    expect(detail.equipment?.equipment_id).toBe(ticket.details!.resources.equipmentId);
    expect(detail.tasks).toHaveLength(4);expect(detail.events).toHaveLength(16);expect(detail.auditLogs).toHaveLength(16);
    expect(detail.events.map(event=>event.step)).toEqual(Array.from({length:16},(_,index)=>index));
    expect((await db.query('SELECT status FROM work_orders')).rows).toEqual([{status:'COMPLETED'}]);
    expect((await db.query('SELECT status FROM plans')).rows).toEqual([{status:'COMPLETED'}]);
    expect((await db.query('SELECT * FROM tasks WHERE status=$1',['COMPLETED'])).rows).toHaveLength(4);
    expect((await db.query('SELECT * FROM audit_logs WHERE trace_id=$1',[ticket.id])).rows).toHaveLength(16);
    await runtime.importTicket(ticket,'OTHER-USER');expect((await runtime.overview()).runs).toHaveLength(1);
  });
  it('rolls back failed steps and stops rather than fabricating completion',async()=>{
    const ticket=generateRailwayTicket('2026-10-30');await runtime.importTicket(ticket,'TEST-USER');
    for(let n=0;n<6;n++)await runtime.tick();
    await db.query(`UPDATE tasks SET status='CANCELLED' WHERE id=$1`,[ticket.id+'-TASK-0']);
    await expect(runtime.tick()).rejects.toThrow();
    const snapshot=await runtime.overview();const run=snapshot.runs.find(r=>r.id===ticket.id);
    expect(run?.status).toBe('ERROR');expect(run?.step).toBe(6);
    expect(snapshot.events.filter(e=>e.run_id===ticket.id)).toHaveLength(6);
  });
  it('automatically imports at 12:30 Beijing time, catches up once and is idempotent',async()=>{
    const settings=railwayScheduleSettings({});
    expect(await dailyRailwayJob(runtime,new Date('2026-10-31T04:29:59Z'),settings)).toBeUndefined();
    await dailyRailwayJob(runtime,new Date('2026-10-31T04:30:00Z'),settings);
    await dailyRailwayJob(runtime,new Date('2026-10-31T12:00:00Z'),settings);
    expect((await runtime.overview()).runs.filter(r=>r.id==='SIM-RAIL-20261031')).toHaveLength(1);
    const disabled=railwayScheduleSettings({PSMS_RAILWAY_AUTO_IMPORT:'false'});
    expect(await dailyRailwayJob(runtime,new Date('2026-11-01T06:00:00Z'),disabled)).toBeUndefined();
  });
  it('does not create another daily order after shutdown while a transition is in flight',async()=>{
    vi.useFakeTimers();vi.setSystemTime(new Date('2026-11-02T05:00:00Z'));
    let finish!:(value:null)=>void;
    const tick=vi.fn(()=>new Promise<null>(resolve=>{finish=resolve;}));
    const importTicket=vi.fn();
    const worker={tick,importTicket} as unknown as RailwayRuntime;
    const stop=startRailwayWorker(worker,vi.fn());stop();finish(null);
    await vi.advanceTimersByTimeAsync(120000);
    expect(tick).toHaveBeenCalledTimes(1);expect(importTicket).not.toHaveBeenCalled();
  });
  it('keeps material totals, wagon totals and explicit simulation resources consistent',()=>{
    const ticket=generateRailwayTicket('2026-11-09'),details=ticket.details!;
    expect(details.dispatch.lines.reduce((sum,line)=>sum+line.plannedQty,0)).toBe(ticket.weight);
    expect(details.wagons.reduce((sum,wagon)=>sum+wagon.plannedWeight,0)).toBe(ticket.weight);
    expect(details.resources.equipmentId).toBe(ticket.id+'-EQ');
    expect(details.companyCode.startsWith('SIM-')).toBe(true);
    expect(railwayTicketSchema.safeParse({...ticket,details:{...details,dispatch:{...details.dispatch,
      lines:[{...details.dispatch.lines[0],plannedQty:ticket.weight+1}]}}}).success).toBe(false);
    expect(railwayTicketSchema.safeParse({...ticket,details:{...details,wagons:[details.wagons[0],details.wagons[0]]}}).success).toBe(false);
  });
  it('preserves legacy daily payloads and shows recorded details without inventing missing fields',async()=>{
    const {details,...legacy}=generateRailwayTicket('2026-10-28');
    await runtime.importTicket(legacy,'LEGACY-SCHEDULER');
    expect(await runtime.generateTicket(legacy.date)).toEqual(legacy);
    await runtime.importTicket(await runtime.generateTicket(legacy.date),'DAILY-RAILWAY-SCHEDULER');
    const detail=await runtime.detail(legacy.id);
    expect(detail.ticket.payload.details).toBeUndefined();expect(detail.plan).toBeNull();expect(detail.tasks).toEqual([]);
    expect(detail.businessDetails?.scenarioCode).toBe(legacy.id+'-SCENARIO');
    expect(detail.detailsProvenance.source).toBe('SIMULATION_SUPPLEMENT');
    expect(detail.run?.actor_id).toBe('LEGACY-SCHEDULER');
    await expect(runtime.importTicket({...legacy,details},'ALTERED-IMPORT')).rejects.toThrow('内容不一致');
    await expect(runtime.detail('OTHER-ORDER')).rejects.toMatchObject({statusCode:400});
    await expect(runtime.detail('SIM-RAIL-20991231')).rejects.toMatchObject({statusCode:404});
  });
  it('stores different business content for historical tickets and supplements once without rewriting originals or events',async()=>{
    const first={...generateRailwayTicket('2026-11-10'),cargoType:'COAL' as const,weight:1200,trainNo:'SIM-10001',details:undefined};
    const second={...generateRailwayTicket('2026-11-11'),cargoType:'STEEL' as const,weight:1500,trainNo:'SIM-10002',trackNo:'T2' as const,details:undefined};
    await runtime.ensureTicket(railwayTicketSchema.parse(first));await runtime.ensureTicket(railwayTicketSchema.parse(second));
    const before=(await db.query('SELECT id,payload,created_at FROM psms_runtime.tickets ORDER BY id')).rows;
    const eventCount=(await db.query('SELECT count(*) AS count FROM psms_runtime.events')).rows[0];
    const firstPass=await runtime.supplementExistingTickets('DETAILS-UPGRADE');
    expect(firstPass.supplemented).toBeGreaterThanOrEqual(2);
    expect((await runtime.supplementExistingTickets('DETAILS-UPGRADE')).supplemented).toBe(0);
    expect((await db.query('SELECT id,payload,created_at FROM psms_runtime.tickets ORDER BY id')).rows).toEqual(before);
    expect((await db.query('SELECT count(*) AS count FROM psms_runtime.events')).rows[0]).toEqual(eventCount);
    const a=await runtime.detail(first.id),b=await runtime.detail(second.id);
    expect(a.businessDetails?.cargoName).toBe('煤炭');expect(b.businessDetails?.cargoName).toBe('钢材');
    expect(a.businessDetails?.workSummary).not.toBe(b.businessDetails?.workSummary);
    expect(a.businessDetails?.dispatch.lines[0].batchCode).not.toBe(b.businessDetails?.dispatch.lines[0].batchCode);
    expect(a.businessDetails?.dispatch.lines.reduce((sum:number,line:{plannedQty:number})=>sum+line.plannedQty,0)).toBe(1200);
    expect(b.businessDetails?.dispatch.lines.reduce((sum:number,line:{plannedQty:number})=>sum+line.plannedQty,0)).toBe(1500);
    expect(a.auditLogs.filter(log=>log.action==='railway:supplement-details')).toHaveLength(1);
    expect(b.auditLogs.filter(log=>log.action==='railway:supplement-details')).toHaveLength(1);
    const listed=(await runtime.overview()).tickets;
    expect(listed.find(ticket=>ticket.id===first.id)?.work_summary).toBe(a.businessDetails?.workSummary);
    expect(listed.find(ticket=>ticket.id===second.id)?.work_summary).toBe(b.businessDetails?.workSummary);
  });
});
