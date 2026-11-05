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
});
