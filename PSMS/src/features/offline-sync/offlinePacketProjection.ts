import { authorize } from '../../auth';
import { transitionState } from '../../commands';
import { mergeStatuses, type OfflinePacket } from '../../contracts';
import type { DemoRootState } from '../../stores';
import { businessLabel } from '../../presentation/businessCopy';
import {
  OFFLINE_RECOVERY_GUIDANCE,
  OFFLINE_SOURCE_DISCLOSURE,
  type OfflinePacketActionAvailability,
  type OfflinePacketBoard,
  type OfflinePacketKpis,
  type OfflinePacketLedgerItem,
  type OfflinePacketProgressState,
  type OfflinePacketQueryContext,
} from './offlinePacketTypes';

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function canReadAreaA(state: DemoRootState): boolean {
  return state.session.dataScope.includes('*')
    || state.session.dataScope.includes('GLOBAL')
    || state.session.dataScope.includes('AREA-A');
}

function strictPacket(packet: OfflinePacket): OfflinePacket {
  return {
    id: packet.id,
    offlinePackageNo: packet.offlinePackageNo,
    terminalId: packet.terminalId,
    workOrderNo: packet.workOrderNo,
    packageVersion: packet.packageVersion,
    serverVersion: packet.serverVersion,
    validation: structuredClone(packet.validation),
    mergeStatus: packet.mergeStatus,
    version: packet.version,
    createdAt: packet.createdAt,
    updatedAt: packet.updatedAt,
  };
}

function progressState(packet: OfflinePacket): OfflinePacketProgressState {
  return packet.mergeStatus === 'PENDING_UPLOAD' ? 'UPLOADING' : packet.mergeStatus;
}

const actionPolicy = {
  upload: { command: 'upload', permission: 'offline:retry' },
  validate: { command: 'validate', permission: 'offline:resolve' },
  merge: { command: 'merge', permission: 'offline:resolve' },
  reject: { command: 'reject', permission: 'offline:resolve' },
  retry: { command: 'retry', permission: 'offline:retry' },
} as const;

function validationValid(packet: OfflinePacket): boolean | undefined {
  return typeof packet.validation.valid === 'boolean' ? packet.validation.valid : undefined;
}

function validationIssues(packet: OfflinePacket): string[] {
  return Array.isArray(packet.validation.issues)
    ? packet.validation.issues.filter((issue): issue is string => typeof issue === 'string')
    : [];
}

function availableActions(
  state: DemoRootState,
  packet: OfflinePacket,
): OfflinePacketActionAvailability {
  return Object.fromEntries(
    Object.entries(actionPolicy).map(([action, policy]) => {
      const transition = transitionState({
        machineId: 'DO-011',
        current: packet.mergeStatus,
        command: policy.command,
      });
      const decision = authorize({
        session: state.session,
        permission: policy.permission,
        objectScope: { type: 'AREA', value: 'AREA-A' },
      });
      const validMerge = action !== 'merge' || validationValid(packet) === true;
      return [action, transition.ok && decision.allow && validMerge];
    }),
  ) as unknown as OfflinePacketActionAvailability;
}

function projectItem(state: DemoRootState, packet: OfflinePacket): OfflinePacketLedgerItem {
  const strict = strictPacket(packet);
  const issues = validationIssues(strict);
  const valid = validationValid(strict);
  return {
    packet: strict,
    progressState: progressState(strict),
    stateFlow: mergeStatuses.map((status) => ({ status, current: status === strict.mergeStatus })),
    versionDelta: strict.packageVersion - strict.serverVersion,
    ...(valid !== undefined ? { validationValid: valid } : {}),
    validationIssues: issues,
    conflict: strict.mergeStatus === 'CONFLICT' || issues.includes('VERSION_CONFLICT'),
    availableActions: availableActions(state, strict),
  };
}

function zeroKpis(): OfflinePacketKpis {
  return {
    cached: 0,
    pendingUpload: 0,
    validating: 0,
    merged: 0,
    conflict: 0,
    rejected: 0,
    retry: 0,
  };
}

function kpisFromItems(items: readonly OfflinePacketLedgerItem[]): OfflinePacketKpis {
  return {
    cached: items.filter(({ packet }) => packet.mergeStatus === 'CACHED').length,
    pendingUpload: items.filter(({ packet }) => packet.mergeStatus === 'PENDING_UPLOAD').length,
    validating: items.filter(({ packet }) => packet.mergeStatus === 'VALIDATING').length,
    merged: items.filter(({ packet }) => packet.mergeStatus === 'MERGED').length,
    conflict: items.filter(({ packet }) => packet.mergeStatus === 'CONFLICT').length,
    rejected: items.filter(({ packet }) => packet.mergeStatus === 'REJECTED').length,
    retry: items.filter(({ packet }) => packet.mergeStatus === 'RETRY').length,
  };
}

function sourceLabels(context: OfflinePacketQueryContext): string[] {
  return [
    context.from ? `来源模块：${businessLabel(context.from)}` : undefined,
    context.scenarioId ? `场景：${context.scenarioId}` : undefined,
    context.packetId ? `离线包：${context.packetId}` : undefined,
    context.terminalId ? `终端：${context.terminalId}` : undefined,
    context.workOrderNo ? `作业单文本：${context.workOrderNo}` : undefined,
    context.mergeStatus ? `状态：${context.mergeStatus}` : undefined,
  ].filter((value): value is string => value !== undefined);
}

function matchesContext(packet: OfflinePacket, context: OfflinePacketQueryContext): boolean {
  return (context.packetId === undefined || packet.id === context.packetId)
    && (context.terminalId === undefined || packet.terminalId === context.terminalId)
    && (context.workOrderNo === undefined || packet.workOrderNo === context.workOrderNo)
    && (context.mergeStatus === undefined || packet.mergeStatus === context.mergeStatus);
}

export function selectOfflinePacketBoard(
  state: DemoRootState,
  context: OfflinePacketQueryContext,
): OfflinePacketBoard {
  const items = canReadAreaA(state)
    ? state.offline.packets
      .filter((packet) => matchesContext(packet, context))
      .sort((left, right) => left.offlinePackageNo.localeCompare(right.offlinePackageNo))
      .map((packet) => projectItem(state, packet))
    : [];

  return deepFreeze({
    items,
    kpis: kpisFromItems(items),
    sourceContext: { ...context },
    sourceContextLabels: sourceLabels(context),
    sourceDisclosure: OFFLINE_SOURCE_DISCLOSURE,
    ...(context.scenarioId === 'SCN-06' ? { recoveryGuidance: OFFLINE_RECOVERY_GUIDANCE } : {}),
  });
}

export function selectOfflinePacketKpis(state: DemoRootState): OfflinePacketKpis {
  if (!canReadAreaA(state)) return deepFreeze(zeroKpis());
  return deepFreeze(kpisFromItems(state.offline.packets.map((packet) => projectItem(state, packet))));
}
