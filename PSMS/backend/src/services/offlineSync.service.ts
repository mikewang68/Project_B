import { randomUUID } from 'node:crypto';
import { OfflinePacket, type OfflinePacketDoc } from '../models/index.js';
import { AppError } from '../middleware/errorHandler.js';
import { writeBusinessAudit } from '../middleware/audit.js';

const DEFAULT_PAGE_SIZE = 20;
const MAX_PAGE_SIZE = 200;

export interface PacketQuery {
  terminalId?: string;
  statuses?: string[];
  page?: number;
  pageSize?: number;
}

export async function listPackets(query: PacketQuery) {
  const filter: Record<string, unknown> = {};
  if (query.terminalId) filter.terminalId = query.terminalId;
  if (query.statuses?.length) filter.status = { $in: query.statuses };

  const page = Math.max(1, query.page ?? 1);
  const pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, query.pageSize ?? DEFAULT_PAGE_SIZE));

  const [items, total] = await Promise.all([
    OfflinePacket.find(filter).sort({ updatedAt: -1 }).skip((page - 1) * pageSize).limit(pageSize).lean(),
    OfflinePacket.countDocuments(filter),
  ]);

  return { items, total, page, pageSize };
}

export async function getPacketById(id: string): Promise<OfflinePacketDoc> {
  const pkt = await OfflinePacket.findById(id).lean<OfflinePacketDoc>();
  if (!pkt) throw new AppError(404, 'PACKET_NOT_FOUND', '离线数据包不存在');
  return pkt;
}

/**
 * 离线包补传。
 *
 * 版本判定：
 *  - 终端版本 < 服务端版本 → 进入 CONFLICT，并记录冲突字段
 *  - 否则接受本次上传，服务端版本 +1
 */
export async function syncPacket(
  id: string,
  operatorId: string,
  payload: Record<string, unknown>,
  version: number,
) {
  const existing = await OfflinePacket.findById(id).lean<OfflinePacketDoc>();

  if (!existing) {
    const created = await OfflinePacket.create({
      _id: id,
      packetId: (payload.packetId as string | undefined) ?? `OFF-${randomUUID().slice(0, 8)}`,
      terminalId: (payload.terminalId as string | undefined) ?? 'unknown',
      operatorId,
      workArea: (payload.workArea as string | undefined) ?? 'AREA-A',
      payload,
      version,
      status: 'SYNCED',
      serverVersion: 1,
      lastSyncAt: new Date(),
      syncAttempts: 1,
    });

    await writeBusinessAudit({
      actorId: operatorId,
      action: 'offline:sync',
      objectType: 'DO-011',
      objectId: id,
      before: {},
      after: { status: 'SYNCED', version, serverVersion: 1 },
      reason: '首次接收离线包',
    });

    return created.toObject();
  }

  const serverVersion = existing.serverVersion ?? 0;

  if (serverVersion > 0 && version < serverVersion) {
    const conflictFields = detectConflicts(existing.payload, payload);
    const conflicted = await OfflinePacket.findByIdAndUpdate(
      id,
      { status: 'CONFLICT', conflictFields, syncAttempts: (existing.syncAttempts ?? 0) + 1 },
      { new: true },
    ).lean();

    await writeBusinessAudit({
      actorId: operatorId,
      action: 'offline:conflict-detected',
      objectType: 'DO-011',
      objectId: id,
      before: { serverVersion },
      after: { status: 'CONFLICT', terminalVersion: version, conflictFields },
      reason: '离线包版本冲突',
    });

    return conflicted;
  }

  const updated = await OfflinePacket.findByIdAndUpdate(
    id,
    {
      payload,
      version,
      serverVersion: serverVersion + 1,
      status: 'SYNCED',
      lastSyncAt: new Date(),
      syncAttempts: (existing.syncAttempts ?? 0) + 1,
      conflictFields: [],
    },
    { new: true },
  ).lean();

  await writeBusinessAudit({
    actorId: operatorId,
    action: 'offline:sync',
    objectType: 'DO-011',
    objectId: id,
    before: { serverVersion },
    after: { status: 'SYNCED', terminalVersion: version, serverVersion: serverVersion + 1 },
    reason: '离线包补传成功',
  });

  return updated;
}

export async function resolvePacketConflict(
  id: string,
  actorId: string,
  resolution: 'ACCEPT_LOCAL' | 'ACCEPT_SERVER' | 'MANUAL_MERGE' | 'DISCARD',
) {
  const pkt = await OfflinePacket.findById(id).lean<OfflinePacketDoc>();
  if (!pkt) throw new AppError(404, 'PACKET_NOT_FOUND', '离线数据包不存在');
  if (pkt.status !== 'CONFLICT') throw new AppError(409, 'INVALID_STATE', '只能解决冲突中的数据包');

  const update: Record<string, unknown> = {
    status: 'RESOLVED',
    resolution,
    resolvedBy: actorId,
    resolvedAt: new Date(),
  };
  // 除"丢弃本地"外，其余处理方式都以服务端版本 +1 记录一次有效合并
  if (resolution !== 'DISCARD') {
    update.serverVersion = (pkt.serverVersion ?? 0) + 1;
  }

  const updated = await OfflinePacket.findByIdAndUpdate(id, update, { new: true }).lean();

  await writeBusinessAudit({
    actorId,
    action: 'offline:resolve-conflict',
    objectType: 'DO-011',
    objectId: id,
    before: { status: pkt.status, conflictFields: pkt.conflictFields ?? [] },
    after: { status: 'RESOLVED', resolution },
    reason: `冲突处置：${resolution}`,
  });

  return updated;
}

/** 逐字段比对服务端与终端载荷，返回不一致的字段名 */
function detectConflicts(
  server: Record<string, unknown>,
  local: Record<string, unknown>,
): string[] {
  const conflicts: string[] = [];
  const keys = new Set([...Object.keys(server ?? {}), ...Object.keys(local ?? {})]);
  for (const key of keys) {
    if (JSON.stringify(server?.[key]) !== JSON.stringify(local?.[key])) conflicts.push(key);
  }
  return conflicts;
}
