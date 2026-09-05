import type { PublicErrorCode } from '../contracts';
import { authorize as authorizePolicy } from '../auth/authorize';
import type { PermissionDecision, PolicyContext } from '../auth/types';
import type {
  CommandDependencies,
  CommandExecutor,
  CommandPermissionEvaluator,
  CommandResult,
  DemoCommand,
} from './types';

type FailureDetails = {
  errorCode: PublicErrorCode;
  message: string;
};

type UnauditedCommandResult =
  | Omit<Extract<CommandResult, { ok: true }>, 'auditLogId'>
  | Omit<Extract<CommandResult, { ok: false }>, 'auditLogId'>;

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function currentState(command: DemoCommand, data: Record<string, unknown>): string {
  if (typeof data.status === 'string') return data.status;
  if (typeof command.payload.current === 'string') return command.payload.current;
  return '';
}

export type CommandPolicyContextResolver = (command: DemoCommand) => PolicyContext;
export type AuthorizationPolicy = (context: PolicyContext) => PermissionDecision;

export function createCommandPermissionEvaluator(
  resolveContext: CommandPolicyContextResolver,
  evaluate: AuthorizationPolicy = authorizePolicy,
): CommandPermissionEvaluator {
  return (command) => {
    const decision = evaluate(resolveContext(command));
    return decision.allow
      ? { allow: true }
      : { allow: false, errorCode: decision.errorCode, message: decision.reason };
  };
}

export function createCommandExecutor(dependencies: CommandDependencies): CommandExecutor {
  const resolvedResults = new Map<string, CommandResult>();

  const auditAndResolve = (
    command: DemoCommand,
    resultInput: UnauditedCommandResult,
  ): CommandResult => {
    const auditLogId = dependencies.nextAuditId();
    const result: CommandResult = resultInput.ok
      ? { ...resultInput, auditLogId }
      : { ...resultInput, auditLogId };
    const appendedAuditId = dependencies.appendAudit({
      command,
      result,
      serverTime: dependencies.now(),
    });
    const resolved = Object.freeze(
      appendedAuditId === result.auditLogId ? result : { ...result, auditLogId: appendedAuditId },
    );
    resolvedResults.set(command.commandId, resolved);
    return resolved;
  };

  const fail = (command: DemoCommand, details: FailureDetails): CommandResult =>
    auditAndResolve(command, {
      ok: false,
      commandId: command.commandId,
      traceId: command.traceId,
      errorCode: details.errorCode,
      message: details.message,
    });

  const execute = async (command: DemoCommand): Promise<CommandResult> => {
    const replay = resolvedResults.get(command.commandId);
    if (replay) return replay;

    const permission = dependencies.authorize(command);
    if (!permission.allow) {
      return fail(command, {
        errorCode: permission.errorCode,
        message: permission.message,
      });
    }

    try {
      dependencies.validate(command);
    } catch (error) {
      return fail(command, {
        errorCode: 'DEMO-SCENARIO-001',
        message: errorMessage(error, 'Command schema validation failed.'),
      });
    }

    let response;
    try {
      response = await dependencies.invokeMock(command);
    } catch (error) {
      return fail(command, {
        errorCode: 'TOS-EXT-001',
        message: errorMessage(error, 'Mock transport failed.'),
      });
    }

    if (!response.ok) {
      return fail(command, {
        errorCode: response.errorCode,
        message: response.message,
      });
    }

    const transition = dependencies.transition({
      machineId: command.entityType,
      current: currentState(command, response.data),
      command: command.action,
    });
    if (!transition.ok) {
      return fail(command, {
        errorCode: 'DEMO-SCENARIO-001',
        message: transition.reason,
      });
    }

    try {
      dependencies.commit(command, response);
    } catch (error) {
      return fail(command, {
        errorCode: 'DEMO-SCENARIO-001',
        message: errorMessage(error, 'Atomic Store commit rejected the candidate.'),
      });
    }

    return auditAndResolve(command, {
      ok: true,
      commandId: command.commandId,
      traceId: command.traceId,
    });
  };

  return { execute };
}
