import { Appointment, type AppointmentDoc } from '../db/tables.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 200;

export interface AppointmentQuery {
  vehiclePlate?: string;
  statuses?: string[];
  plannedDate?: string;
  page?: number;
  pageSize?: number;
}

export async function listAppointments(query: AppointmentQuery) {
  const filter: Record<string, unknown> = {};
  if (query.vehiclePlate) filter.vehiclePlate = { $regex: query.vehiclePlate, $options: 'i' };
  if (query.statuses?.length) filter.status = { $in: query.statuses };
  if (query.plannedDate) {
    const start = new Date(query.plannedDate);
    const end = new Date(query.plannedDate);
    end.setDate(end.getDate() + 1);
    filter.plannedArriveTime = { $gte: start, $lte: end };
  }

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [items, total] = await Promise.all([
    Appointment.find(filter)
      .sort({ plannedArriveTime: 1 })
      .skip((page - 1) * pageSize)
      .limit(pageSize)
      .lean(),
    Appointment.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getAppointmentById(id: string): Promise<AppointmentDoc> {
  const apt = await Appointment.findById(id).lean<AppointmentDoc>();
  if (!apt) throw new AppError(404, 'APPOINTMENT_NOT_FOUND', '预约不存在');
  return apt;
}

async function requireAppointment(id: string): Promise<AppointmentDoc> {
  const apt = await Appointment.findById(id).lean<AppointmentDoc>();
  if (!apt) throw new AppError(404, 'APPOINTMENT_NOT_FOUND', '预约不存在');
  return apt;
}

async function audit(
  actorId: string,
  action: string,
  id: string,
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  reason: string,
) {
  await writeBusinessAudit({
    actorId,
    action,
    objectType: 'DO-008',
    objectId: id,
    before,
    after,
    reason,
  });
}

export async function approveAppointment(id: string, actorId = 'system') {
  const apt = await requireAppointment(id);
  if (apt.status !== 'PENDING') throw new AppError(409, 'INVALID_STATE', '只能审批待处理的预约');

  const updated = await Appointment.findByIdAndUpdate(id, { status: 'APPROVED' }, { new: true }).lean();
  await audit(actorId, 'appointment:approve', id, { status: apt.status }, { status: 'APPROVED' }, '预约审核通过');
  return updated;
}

/** 签到入场：排队号按当前 QUEUED 数量自增 */
export async function checkInAppointment(id: string, actorId = 'system') {
  const apt = await requireAppointment(id);

  const queuedCount = await Appointment.countDocuments({ status: 'QUEUED' });
  const now = new Date();

  const updated = await Appointment.findByIdAndUpdate(
    id,
    {
      status: 'QUEUED',
      checkInTime: now,
      actualArriveTime: now,
      queueNumber: queuedCount + 1,
    },
    { new: true },
  ).lean();

  await audit(
    actorId,
    'appointment:check-in',
    id,
    { status: apt.status },
    { status: 'QUEUED', queueNumber: queuedCount + 1 },
    '车辆签到并进入排队',
  );
  return updated;
}

export async function callAppointment(id: string, gateNo?: string, actorId = 'system') {
  const apt = await requireAppointment(id);
  if (apt.status !== 'QUEUED') throw new AppError(409, 'INVALID_STATE', '只能叫号排队中的车辆');

  const updated = await Appointment.findByIdAndUpdate(
    id,
    { status: 'CALLED', calledAt: new Date(), ...(gateNo ? { gateNo } : {}) },
    { new: true },
  ).lean();

  await audit(
    actorId,
    'appointment:call',
    id,
    { status: apt.status },
    { status: 'CALLED', gateNo: gateNo ?? null },
    '叫号放行',
  );
  return updated;
}

export async function enterAppointment(id: string, actorId = 'system') {
  const apt = await requireAppointment(id);
  const updated = await Appointment.findByIdAndUpdate(
    id,
    { status: 'ON_SITE', enterTime: new Date() },
    { new: true },
  ).lean();
  await audit(actorId, 'appointment:enter', id, { status: apt.status }, { status: 'ON_SITE' }, '车辆入场');
  return updated;
}

export async function completeAppointment(id: string, actorId = 'system') {
  const apt = await requireAppointment(id);
  const updated = await Appointment.findByIdAndUpdate(
    id,
    { status: 'COMPLETED', exitTime: new Date() },
    { new: true },
  ).lean();
  await audit(actorId, 'appointment:complete', id, { status: apt.status }, { status: 'COMPLETED' }, '作业完成离场');
  return updated;
}
