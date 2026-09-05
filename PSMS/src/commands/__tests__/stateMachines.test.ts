import { describe, expect, it } from 'vitest';

import {
  stateMachineCatalog,
  transitionState,
  type MachineId,
} from '../stateMachines';

const expectedMachines = {
  'DO-001': {
    states: [
      'RECEIVED',
      'VALIDATING',
      'PENDING_CONFIRM',
      'CONFIRMED',
      'DECOMPOSED',
      'BLOCKED',
      'CANCELLED',
      'ADJUSTED',
    ],
    commands: ['sync', 'validate', 'confirm', 'adjust', 'decompose'],
    transitions: [
      ['RECEIVED', 'sync', 'VALIDATING'],
      ['VALIDATING', 'validate', 'PENDING_CONFIRM'],
      ['PENDING_CONFIRM', 'confirm', 'CONFIRMED'],
      ['CONFIRMED', 'adjust', 'ADJUSTED'],
      ['BLOCKED', 'adjust', 'ADJUSTED'],
      ['ADJUSTED', 'confirm', 'CONFIRMED'],
      ['CONFIRMED', 'decompose', 'DECOMPOSED'],
      ['ADJUSTED', 'decompose', 'DECOMPOSED'],
    ],
  },
  'DO-005': {
    states: [
      'DRAFT',
      'READY',
      'DISPATCHED',
      'ACKNOWLEDGED',
      'IN_PROGRESS',
      'COMPLETED',
      'PAUSED',
      'BLOCKED',
      'FAILED',
      'CANCELLED',
    ],
    commands: ['assign', 'dispatch', 'ack', 'start', 'pause', 'complete'],
    transitions: [
      ['DRAFT', 'assign', 'READY'],
      ['READY', 'dispatch', 'DISPATCHED'],
      ['DISPATCHED', 'ack', 'ACKNOWLEDGED'],
      ['ACKNOWLEDGED', 'start', 'IN_PROGRESS'],
      ['PAUSED', 'start', 'IN_PROGRESS'],
      ['IN_PROGRESS', 'pause', 'PAUSED'],
      ['IN_PROGRESS', 'complete', 'COMPLETED'],
    ],
  },
  'DO-008': {
    states: [
      'DRAFT',
      'SUBMITTED',
      'APPROVED',
      'QUEUED',
      'CALLED',
      'ENTERED',
      'OPERATING',
      'RELEASED',
      'EXITED',
      'REJECTED',
      'NEED_FIX',
      'EXCEPTION',
    ],
    commands: ['submit', 'approve', 'call', 'gateIn', 'release', 'gateOut'],
    transitions: [
      ['DRAFT', 'submit', 'SUBMITTED'],
      ['NEED_FIX', 'submit', 'SUBMITTED'],
      ['SUBMITTED', 'approve', 'APPROVED'],
      ['APPROVED', 'call', 'CALLED'],
      ['QUEUED', 'call', 'CALLED'],
      ['CALLED', 'gateIn', 'ENTERED'],
      ['OPERATING', 'release', 'RELEASED'],
      ['RELEASED', 'gateOut', 'EXITED'],
    ],
  },
  'DO-009': {
    states: ['OPEN', 'ACKNOWLEDGED', 'HANDLING', 'PENDING_REVIEW', 'CLOSED', 'ESCALATED', 'REOPENED'],
    commands: ['ack', 'assign', 'handle', 'review', 'close', 'reopen'],
    transitions: [
      ['OPEN', 'ack', 'ACKNOWLEDGED'],
      ['REOPENED', 'ack', 'ACKNOWLEDGED'],
      ['ACKNOWLEDGED', 'assign', 'HANDLING'],
      ['HANDLING', 'handle', 'PENDING_REVIEW'],
      ['PENDING_REVIEW', 'review', 'HANDLING'],
      ['PENDING_REVIEW', 'close', 'CLOSED'],
      ['CLOSED', 'reopen', 'REOPENED'],
    ],
  },
  'DO-010': {
    states: [
      'TRIGGERED',
      'ACTION_ISSUED',
      'WAITING_RECEIPT',
      'LOCKED',
      'RESET_REQUESTED',
      'APPROVED',
      'RESTORED',
      'FAILED',
      'OVERRIDE_PENDING',
      'OVERRIDDEN',
    ],
    commands: ['trigger', 'receipt', 'requestReset', 'approve', 'restore', 'requestOverride'],
    transitions: [
      ['TRIGGERED', 'trigger', 'ACTION_ISSUED'],
      ['ACTION_ISSUED', 'receipt', 'WAITING_RECEIPT'],
      ['WAITING_RECEIPT', 'receipt', 'LOCKED'],
      ['LOCKED', 'requestReset', 'RESET_REQUESTED'],
      ['RESET_REQUESTED', 'approve', 'APPROVED'],
      ['APPROVED', 'restore', 'RESTORED'],
      ['LOCKED', 'requestOverride', 'OVERRIDE_PENDING'],
      ['OVERRIDE_PENDING', 'approve', 'OVERRIDDEN'],
    ],
  },
  'DO-011': {
    states: ['CACHED', 'PENDING_UPLOAD', 'VALIDATING', 'MERGED', 'CONFLICT', 'REJECTED', 'RETRY'],
    commands: ['upload', 'validate', 'merge', 'reject', 'retry'],
    transitions: [
      ['CACHED', 'upload', 'PENDING_UPLOAD'],
      ['RETRY', 'upload', 'PENDING_UPLOAD'],
      ['PENDING_UPLOAD', 'validate', 'VALIDATING'],
      ['VALIDATING', 'merge', 'MERGED'],
      ['VALIDATING', 'reject', 'REJECTED'],
      ['CONFLICT', 'reject', 'REJECTED'],
      ['CONFLICT', 'retry', 'RETRY'],
      ['REJECTED', 'retry', 'RETRY'],
    ],
  },
  'DO-015': {
    states: ['DRAFT', 'SUBMITTED', 'APPROVED', 'PUBLISHED', 'ROLLED_BACK'],
    commands: ['edit', 'submit', 'approve', 'publish', 'rollback'],
    transitions: [
      ['DRAFT', 'edit', 'DRAFT'],
      ['DRAFT', 'submit', 'SUBMITTED'],
      ['SUBMITTED', 'approve', 'APPROVED'],
      ['APPROVED', 'publish', 'PUBLISHED'],
      ['PUBLISHED', 'rollback', 'ROLLED_BACK'],
    ],
  },
  'SM-007': {
    states: ['ACCEPTED', 'EXECUTING', 'SUCCESS', 'FAILED', 'TIMEOUT'],
    commands: ['accept', 'execute', 'succeed', 'fail', 'timeout', 'retry'],
    transitions: [
      ['ACCEPTED', 'accept', 'ACCEPTED'],
      ['ACCEPTED', 'execute', 'EXECUTING'],
      ['EXECUTING', 'succeed', 'SUCCESS'],
      ['EXECUTING', 'fail', 'FAILED'],
      ['EXECUTING', 'timeout', 'TIMEOUT'],
      ['FAILED', 'retry', 'EXECUTING'],
      ['TIMEOUT', 'retry', 'EXECUTING'],
    ],
  },
} as const;

describe('C03 frozen state-machine catalog', () => {
  it('contains exactly the eight frozen machine identifiers', () => {
    expect(Object.keys(stateMachineCatalog)).toEqual(Object.keys(expectedMachines));
  });

  it.each(Object.entries(expectedMachines))(
    '%s exposes every frozen state, command, and explicit transition',
    (machineId, expected) => {
      expect(stateMachineCatalog[machineId as MachineId]).toEqual(expected);
    },
  );

  for (const [machineId, machine] of Object.entries(expectedMachines)) {
    const transitions: readonly (readonly [string, string, string])[] = machine.transitions;

    it.each(transitions)(
      `${machineId} allows %s + %s -> %s`,
      (current, command, next) => {
        expect(
          transitionState({ machineId: machineId as MachineId, current, command }),
        ).toEqual({ ok: true, previous: current, next, command });
      },
    );
  }

  it.each([
    ['DO-001', 'BLOCKED', 'decompose'],
    ['DO-005', 'DISPATCHED', 'start'],
    ['DO-008', 'DRAFT', 'approve'],
    ['DO-009', 'OPEN', 'close'],
    ['DO-010', 'RESET_REQUESTED', 'restore'],
    ['DO-011', 'CACHED', 'merge'],
    ['DO-015', 'DRAFT', 'approve'],
    ['DO-015', 'SUBMITTED', 'publish'],
    ['DO-015', 'ROLLED_BACK', 'edit'],
    ['SM-007', 'SUCCESS', 'retry'],
  ] as const)('%s rejects the forbidden %s + %s path', (machineId, current, command) => {
    expect(transitionState({ machineId, current, command })).toEqual({
      ok: false,
      previous: current,
      command,
      reason: 'FORBIDDEN_TRANSITION',
    });
  });

  it('rejects an unknown state before attempting a transition', () => {
    expect(transitionState({ machineId: 'DO-001', current: 'UNKNOWN', command: 'sync' })).toEqual({
      ok: false,
      previous: 'UNKNOWN',
      command: 'sync',
      reason: 'UNKNOWN_STATE',
    });
  });

  it('rejects an unknown command for a known state', () => {
    expect(transitionState({ machineId: 'DO-001', current: 'RECEIVED', command: 'unknown' })).toEqual({
      ok: false,
      previous: 'RECEIVED',
      command: 'unknown',
      reason: 'UNKNOWN_COMMAND',
    });
  });

  it('C04 confirms a supplemented plan without changing another DO-001 path', () => {
    expect(transitionState({ machineId: 'DO-001', current: 'ADJUSTED', command: 'confirm' }))
      .toEqual({ ok: true, previous: 'ADJUSTED', next: 'CONFIRMED', command: 'confirm' });
    expect(transitionState({ machineId: 'DO-001', current: 'BLOCKED', command: 'confirm' }))
      .toMatchObject({ ok: false, reason: 'FORBIDDEN_TRANSITION' });
  });
});
