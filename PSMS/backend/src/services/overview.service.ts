import { connectClient } from '../db/openGaussClient.js';
import { AppError } from '../middleware/errorHandler.js';

export function overviewScope(scopes: readonly string[], workArea: unknown, date: unknown) {
  if (typeof workArea !== 'string' || !/^AREA-[A-Z0-9_-]+$/.test(workArea)
      || typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)
      || !Number.isFinite(Date.parse(date + 'T00:00:00Z'))
      || new Date(date + 'T00:00:00Z').toISOString().slice(0, 10) !== date) {
    throw new AppError(400, 'INVALID_OVERVIEW_FILTER', '请选择有效的日期和作业区');
  }
  const global = scopes.includes('*') || scopes.includes('GLOBAL');
  if (!global && !scopes.includes(workArea)) throw new AppError(403, 'DATA_SCOPE_DENIED', '无权读取该作业区');
  return { workArea, date, global };
}

/** Aggregates one read-only snapshot. Missing scope support is reported, never converted to zero. */
export async function readBusinessOverview(scopes: readonly string[], workArea: unknown, date: unknown,
    connect = connectClient) {
  const filter = overviewScope(scopes, workArea, date);
  const db = await connect();
  try {
    await db.query('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY');
    const plans = (await db.query(`SELECT count(*)::int AS total,
      count(CASE WHEN status='PENDING_CONFIRM' THEN 1 END)::int AS pending,
      count(CASE WHEN status='CONFIRMED' THEN 1 END)::int AS confirmed,
      count(CASE WHEN status='BLOCKED' THEN 1 END)::int AS blocked
      FROM public.plans WHERE valid=1 AND work_area=$1
        AND (arrive_time AT TIME ZONE 'Asia/Shanghai')::date=$2::date`, [filter.workArea, filter.date])).rows[0];
    const workOrders = (await db.query(`SELECT count(*)::int AS total FROM public.work_orders
      WHERE valid=1 AND work_area=$1 AND status='COMPLETED'
        AND (actual_end_time AT TIME ZONE 'Asia/Shanghai')::date=$2::date`, [filter.workArea, filter.date])).rows[0];
    const equipment = (await db.query(`SELECT count(*)::int AS total,
      count(CASE WHEN status IN ('IDLE','AVAILABLE','ONLINE') THEN 1 END)::int AS available,
      count(CASE WHEN GREATEST(last_heartbeat,last_telemetry_at)>CURRENT_TIMESTAMP-INTERVAL '120 seconds' THEN 1 END)::int AS fresh
      FROM public.equipment WHERE valid=1 AND work_area=$1`, [filter.workArea])).rows[0];
    const exceptions = (await db.query(`SELECT count(*)::int AS total FROM public.exceptions
      WHERE valid=1 AND work_area=$1 AND status NOT IN ('CLOSED','RESOLVED')`, [filter.workArea])).rows[0];
    const risks = (await db.query(`SELECT id,'EXCEPTION' AS source,type,status,severity AS level
      FROM public.exceptions WHERE valid=1 AND work_area=$1 AND status NOT IN ('CLOSED','RESOLVED')
      UNION ALL SELECT id,'INTERLOCK' AS source,type,status,'MAJOR' AS level
      FROM public.interlocks WHERE valid=1 AND work_area=$1 AND status IN ('TRIGGERED','BLOCKED','OVERRIDDEN')
      UNION ALL SELECT id,'OFFLINE' AS source,'OFFLINE_PACKET' AS type,status,'MAJOR' AS level
      FROM public.offline_packets WHERE valid=1 AND work_area=$1 AND status IN ('CONFLICT','REJECTED','FAILED')
      ORDER BY id LIMIT 50`, [filter.workArea])).rows;
    // Appointments have no work-area column. Do not expose global counts to an area-only account.
    const appointments = filter.global ? (await db.query(`SELECT count(*)::int AS total
      FROM public.appointments WHERE valid=1 AND status IN ('QUEUED','CALLED')`)).rows[0] : null;
    const dates = (await db.query(`SELECT min((arrive_time AT TIME ZONE 'Asia/Shanghai')::date)::text AS earliest,
      max((arrive_time AT TIME ZONE 'Asia/Shanghai')::date)::text AS latest,
      count(*)::int AS total FROM public.plans WHERE valid=1 AND work_area=$1`, [filter.workArea])).rows[0];
    await db.query('COMMIT');
    return { workArea: filter.workArea, date: filter.date, generatedAt: new Date().toISOString(),
      timeZone: 'Asia/Shanghai',
      kpis: { totalPlans: plans.total, pendingConfirmPlans: plans.pending, confirmedPlans: plans.confirmed,
        blockedPlans: plans.blocked, completedWorkOrders: workOrders.total,
        waitingVehicles: appointments?.total ?? null,
        availableResourceRate: equipment.total && equipment.fresh===equipment.total ? Math.round(equipment.available / equipment.total * 100) : null,
        openExceptions: exceptions.total },
      availability: { waitingVehicles: filter.global ? 'READY' : 'UNSUPPORTED_AREA_SCOPE',
        availableResourceRate: !equipment.total ? 'NO_EQUIPMENT' : equipment.fresh===equipment.total ? 'READY' : 'STALE_TELEMETRY',
        yardLayout: 'NOT_CONNECTED' },
      risks, planRange: dates, waitingVehicleScope: filter.global ? 'GLOBAL' : null };
  } catch (error) {
    try { await db.query('ROLLBACK'); } catch { /* keep the original read error */ }
    throw error;
  } finally { db.release(); }
}
