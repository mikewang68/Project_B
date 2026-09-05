import { describe, expect, it } from 'vitest';

import {
  createCommandExecutor,
  type AuditAppenderInput,
  type CommandDependencies,
  type CommandResult,
  type DemoCommand,
} from '..';

const command: DemoCommand = {
  commandId: 'CMD-001',
  action: 'sync',
  entityType: 'DO-001',
  entityId: 'PLAN-001',
  expectedVersion: 1,
  payload: { scenarioId: 'SCN-01' },
  actor: {
    actorId: 'USER-001',
    roleCode: 'DISPATCHER',
    dataScope: ['AREA-A'],
    online: true,
  },
  traceId: 'TRACE-C03-001',
  clientTime: '2026-07-19T08:00:00+08:00',
};

function createHarness(overrides: Partial<CommandDependencies> = {}) {
  const events: string[] = [];
  const auditInputs: AuditAppenderInput[] = [];
  let auditSequence = 1;

  const defaults: CommandDependencies = {
    authorize: () => {
      events.push('permission');
      return { allow: true };
    },
    validate: () => {
      events.push('schema');
    },
    invokeMock: async (input) => {
      events.push('Mock');
      return {
        ok: true,
        data: { id: input.entityId, status: 'RECEIVED', version: input.expectedVersion },
        auditLogId: 'MOCK-AUDIT-001',
        traceId: input.traceId,
      };
    },
    transition: (request) => {
      events.push('transition');
      return {
        ok: true,
        previous: request.current,
        next: 'VALIDATING',
        command: request.command,
      };
    },
    commit: () => {
      events.push('commit');
    },
    appendAudit: (input) => {
      events.push('audit');
      auditInputs.push(structuredClone(input));
      return input.result.auditLogId;
    },
    nextAuditId: () => `AUDIT-C03-${String(auditSequence++).padStart(3, '0')}`,
    now: () => '2026-07-19T08:00:01+08:00',
  };

  return {
    events,
    auditInputs,
    executor: createCommandExecutor({ ...defaults, ...overrides }),
  };
}

describe('C03 command pipeline', () => {
  it('executes permission -> schema -> Mock -> transition -> commit -> audit exactly once', async () => {
    const harness = createHarness();

    const result = await harness.executor.execute(command);

    expect(result).toEqual({
      ok: true,
      commandId: command.commandId,
      traceId: command.traceId,
      auditLogId: 'AUDIT-C03-001',
    });
    expect(harness.events).toEqual([
      'permission',
      'schema',
      'Mock',
      'transition',
      'commit',
      'audit',
    ]);
    expect(harness.auditInputs).toHaveLength(1);
    expect(harness.auditInputs[0].serverTime).toBe('2026-07-19T08:00:01+08:00');
  });

  it('audits a permission denial without schema, Mock, transition, or commit', async () => {
    const events: string[] = [];
    const harness = createHarness({
      authorize: () => {
        events.push('permission');
        return { allow: false, errorCode: 'TOS-AUTH-001', message: 'denied' };
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-AUTH-001', message: 'denied' });
    expect(events).toEqual(['permission', 'audit']);
    expect(harness.events).toEqual([]);
  });

  it('audits schema failure and never calls Mock, transition, or commit', async () => {
    const events: string[] = [];
    const harness = createHarness({
      authorize: () => {
        events.push('permission');
        return { allow: true };
      },
      validate: () => {
        events.push('schema');
        throw new Error('payload invalid');
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: 'payload invalid',
    });
    expect(events).toEqual(['permission', 'schema', 'audit']);
    expect(harness.events).toEqual([]);
  });

  it('propagates a Mock error envelope and never transitions or commits', async () => {
    const events: string[] = [];
    const harness = createHarness({
      authorize: () => {
        events.push('permission');
        return { allow: true };
      },
      validate: () => {
        events.push('schema');
      },
      invokeMock: async () => {
        events.push('Mock');
        return {
          ok: false,
          errorCode: 'TOS-EXT-001',
          message: 'upstream unavailable',
          auditLogId: 'MOCK-AUDIT-FAIL',
          traceId: command.traceId,
        };
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({
      ok: false,
      errorCode: 'TOS-EXT-001',
      message: 'upstream unavailable',
    });
    expect(events).toEqual(['permission', 'schema', 'Mock', 'audit']);
    expect(harness.events).toEqual([]);
  });

  it('turns a thrown Mock transport failure into TOS-EXT-001 without a commit', async () => {
    const events: string[] = [];
    const harness = createHarness({
      authorize: () => {
        events.push('permission');
        return { allow: true };
      },
      validate: () => {
        events.push('schema');
      },
      invokeMock: async () => {
        events.push('Mock');
        throw new Error('network down');
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({ ok: false, errorCode: 'TOS-EXT-001', message: 'network down' });
    expect(events).toEqual(['permission', 'schema', 'Mock', 'audit']);
  });

  it('audits a transition rejection without committing', async () => {
    const events: string[] = [];
    const harness = createHarness({
      authorize: () => {
        events.push('permission');
        return { allow: true };
      },
      validate: () => {
        events.push('schema');
      },
      invokeMock: async () => {
        events.push('Mock');
        return {
          ok: true,
          data: { id: command.entityId, status: 'BLOCKED', version: 1 },
          auditLogId: 'MOCK-AUDIT-001',
          traceId: command.traceId,
        };
      },
      transition: (request) => {
        events.push('transition');
        return {
          ok: false,
          previous: request.current,
          command: request.command,
          reason: 'FORBIDDEN_TRANSITION',
        };
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({
      ok: false,
      errorCode: 'DEMO-SCENARIO-001',
      message: 'FORBIDDEN_TRANSITION',
    });
    expect(events).toEqual(['permission', 'schema', 'Mock', 'transition', 'audit']);
    expect(harness.events).toEqual([]);
  });

  it('returns the first resolved result for commandId replay without a second side effect', async () => {
    const harness = createHarness();

    const first = await harness.executor.execute(command);
    const second = await harness.executor.execute({ ...command, payload: { scenarioId: 'SCN-07' } });

    expect(second).toBe(first);
    expect(harness.events).toEqual([
      'permission',
      'schema',
      'Mock',
      'transition',
      'commit',
      'audit',
    ]);
    expect(harness.auditInputs).toHaveLength(1);
  });

  it('returns DEMO-VERSION-001 before schema and Mock when expectedVersion differs', async () => {
    const actualVersion = 2;
    const events: string[] = [];
    const harness = createHarness({
      authorize: (input) => {
        events.push('permission');
        return input.expectedVersion === actualVersion
          ? { allow: true }
          : {
              allow: false,
              errorCode: 'DEMO-VERSION-001',
              message: `expected ${input.expectedVersion}, actual ${actualVersion}`,
            };
      },
      appendAudit: (input) => {
        events.push('audit');
        return input.result.auditLogId;
      },
    });

    const result = await harness.executor.execute(command);

    expect(result).toMatchObject({
      ok: false,
      errorCode: 'DEMO-VERSION-001',
      message: 'expected 1, actual 2',
    });
    expect(events).toEqual(['permission', 'audit']);
    expect(harness.events).toEqual([]);
  });

  it('does not cache a command when audit append fails before a resolved result exists', async () => {
    let auditAttempts = 0;
    const harness = createHarness({
      appendAudit: (input) => {
        auditAttempts += 1;
        if (auditAttempts === 1) throw new Error('audit unavailable');
        return input.result.auditLogId;
      },
    });

    await expect(harness.executor.execute(command)).rejects.toThrow('audit unavailable');
    const result: CommandResult = await harness.executor.execute(command);

    expect(result.ok).toBe(true);
    expect(auditAttempts).toBe(2);
  });
});
