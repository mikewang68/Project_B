export type MachineId =
  | 'DO-001'
  | 'DO-005'
  | 'DO-008'
  | 'DO-009'
  | 'DO-010'
  | 'DO-011'
  | 'DO-015'
  | 'SM-007';

export type MachineState = string;
export type MachineCommand = string;

export type TransitionRequest = {
  machineId: MachineId;
  current: MachineState;
  command: MachineCommand;
};

export type TransitionResult =
  | { ok: true; previous: string; next: string; command: string }
  | {
      ok: false;
      previous: string;
      command: string;
      reason: 'UNKNOWN_STATE' | 'UNKNOWN_COMMAND' | 'FORBIDDEN_TRANSITION';
    };

type TransitionTriple = readonly [current: string, command: string, next: string];

type StateMachineDefinition = {
  readonly states: readonly string[];
  readonly commands: readonly string[];
  readonly transitions: readonly TransitionTriple[];
};

export const stateMachineCatalog = {
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
} as const satisfies Record<MachineId, StateMachineDefinition>;

export function transitionState(request: TransitionRequest): TransitionResult {
  const machine = stateMachineCatalog[request.machineId];

  if (!machine.states.some((state) => state === request.current)) {
    return {
      ok: false,
      previous: request.current,
      command: request.command,
      reason: 'UNKNOWN_STATE',
    };
  }

  if (!machine.commands.some((command) => command === request.command)) {
    return {
      ok: false,
      previous: request.current,
      command: request.command,
      reason: 'UNKNOWN_COMMAND',
    };
  }

  const transition = machine.transitions.find(
    ([current, command]) => current === request.current && command === request.command,
  );

  if (!transition) {
    return {
      ok: false,
      previous: request.current,
      command: request.command,
      reason: 'FORBIDDEN_TRANSITION',
    };
  }

  return {
    ok: true,
    previous: request.current,
    next: transition[2],
    command: request.command,
  };
}
