import type {
  ApiErrorEnvelope,
  ApiSuccessEnvelope,
  PublicErrorCode,
} from '../contracts';
import { transitionState, type MachineId } from './stateMachines';

export type CommandActor = {
  actorId: string;
  roleCode: string;
  dataScope: readonly string[];
  online: boolean;
};

export type DemoCommand<TPayload extends Record<string, unknown> = Record<string, unknown>> = {
  commandId: string;
  action: string;
  entityType: MachineId;
  entityId: string;
  expectedVersion: number;
  payload: TPayload;
  actor: CommandActor;
  traceId: string;
  clientTime: string;
};

export type CommandPermissionDecision =
  | { allow: true }
  | {
      allow: false;
      errorCode: 'TOS-AUTH-001' | 'DEMO-VERSION-001';
      message: string;
    };

export type CommandPermissionEvaluator = (command: DemoCommand) => CommandPermissionDecision;

export type CommandResult =
  | { ok: true; commandId: string; traceId: string; auditLogId: string }
  | {
      ok: false;
      commandId: string;
      traceId: string;
      auditLogId: string;
      errorCode: PublicErrorCode;
      message: string;
    };

export type AuditAppenderInput = {
  command: DemoCommand;
  result: CommandResult;
  serverTime: string;
};

export type AuditAppender = (input: AuditAppenderInput) => string;

export type CommandDependencies = {
  authorize: CommandPermissionEvaluator;
  validate: (command: DemoCommand) => void;
  invokeMock: (command: DemoCommand) => Promise<ApiSuccessEnvelope | ApiErrorEnvelope>;
  transition: typeof transitionState;
  commit: (command: DemoCommand, response: ApiSuccessEnvelope) => void;
  appendAudit: AuditAppender;
  nextAuditId: () => string;
  now: () => string;
};

export type CommandExecutor = {
  execute: (command: DemoCommand) => Promise<CommandResult>;
};
