import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import * as contractExports from '../index';
import {
  apiErrorEnvelopeSchema,
  apiSuccessEnvelopeSchema,
  domainObjectCounts,
  domainObjectIds,
  domainSchemas,
  enumValuesByComponent,
  publicErrorCodeSchema,
  publicErrorCodes,
  requestSchemas,
} from '../index';

type JsonObject = Record<string, unknown>;

type BaselineFixtures = {
  objects: Record<string, JsonObject[]>;
  errorCodes: Array<{ code: string }>;
};

type OpenApiDocument = {
  components: {
    schemas: Record<
      string,
      {
        enum?: string[];
        properties?: Record<string, unknown>;
        required?: string[];
      }
    >;
  };
  paths: Record<
    string,
    Record<
      string,
      {
        requestBody?: {
          content: {
            'application/json': {
              schema: { $ref: string };
              example: JsonObject;
            };
          };
        };
      }
    >
  >;
};

function readJson<T>(fileName: string): T {
  return JSON.parse(readFileSync(join(process.cwd(), 'docs', 'baseline', fileName), 'utf8')) as T;
}

describe('C02 领域契约', () => {
  const fixtures = readJson<BaselineFixtures>('demo-fixtures.json');
  const openapi = readJson<OpenApiDocument>('openapi.yaml');

  it('只导出 DO-001 至 DO-015，并逐条接受冻结 fixture', () => {
    const expectedIds = Array.from({ length: 15 }, (_, index) => `DO-${String(index + 1).padStart(3, '0')}`);

    expect(domainObjectIds).toEqual(expectedIds);
    expect(Object.keys(domainSchemas)).toEqual(expectedIds);
    expect(domainObjectCounts).toEqual({
      'DO-001': 3,
      'DO-002': 8,
      'DO-003': 4,
      'DO-004': 8,
      'DO-005': 12,
      'DO-006': 12,
      'DO-007': 10,
      'DO-008': 6,
      'DO-009': 5,
      'DO-010': 4,
      'DO-011': 4,
      'DO-012': 3,
      'DO-013': 9,
      'DO-014': 13,
      'DO-015': 1,
    });

    for (const objectId of expectedIds) {
      const records = fixtures.objects[objectId];
      expect(records, objectId).toHaveLength(domainObjectCounts[objectId as keyof typeof domainObjectCounts]);
      for (const record of records) {
        expect(domainSchemas[objectId as keyof typeof domainSchemas].safeParse(record).success, objectId).toBe(true);
      }
    }
  });

  it('严格拒绝未知字段、snake_case 别名和非法枚举', () => {
    const plan = fixtures.objects['DO-001'][0];
    const role = fixtures.objects['DO-014'][0];

    expect(domainSchemas['DO-001'].safeParse({ ...plan, unexpectedField: true }).success).toBe(false);
    expect(
      domainSchemas['DO-001'].safeParse({
        ...plan,
        planBatchNo: undefined,
        plan_batch_no: plan.planBatchNo,
      }).success,
    ).toBe(false);
    expect(domainSchemas['DO-001'].safeParse({ ...plan, cargoType: 'NOT_A_CARGO' }).success).toBe(false);
    expect(domainSchemas['DO-014'].safeParse({ ...role, roleCode: 'ROOT' }).success).toBe(false);
  });

  it('15 个运行时字段集合与 OpenAPI required 完全一致', () => {
    for (const [index, objectId] of domainObjectIds.entries()) {
      const component = openapi.components.schemas[`DO${String(index + 1).padStart(3, '0')}`];
      const shape = (domainSchemas[objectId] as unknown as { shape: Record<string, unknown> }).shape;

      expect(Object.keys(shape), objectId).toEqual(Object.keys(component.properties ?? {}));
      expect(Object.keys(shape), objectId).toEqual(component.required);
    }
  });

  it('全部公共枚举成员与 OpenAPI 逐项一致', () => {
    const openApiEnums = Object.fromEntries(
      Object.entries(openapi.components.schemas)
        .filter(([, schema]) => schema.enum)
        .map(([name, schema]) => [name, schema.enum]),
    );

    expect(enumValuesByComponent).toEqual(openApiEnums);
  });

  it('公开错误码恰好为冻结的九项', () => {
    const expected = fixtures.errorCodes.map(({ code }) => code);

    expect(publicErrorCodes).toEqual(expected);
    expect(expected).toHaveLength(9);
    for (const code of expected) {
      expect(publicErrorCodeSchema.safeParse(code).success).toBe(true);
    }
    expect(publicErrorCodeSchema.safeParse('UNKNOWN-ERROR-CODE').success).toBe(false);
  });

  it('成功与失败信封包含 traceId 和 auditLogId', () => {
    expect(
      apiSuccessEnvelopeSchema.safeParse({
        ok: true,
        data: { id: 'RESULT-001' },
        auditLogId: 'AUD-001',
        traceId: 'TRACE-001',
      }).success,
    ).toBe(true);
    expect(
      apiErrorEnvelopeSchema.safeParse({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: 'invalid request',
        auditLogId: 'AUD-001',
        traceId: 'TRACE-001',
      }).success,
    ).toBe(true);
    expect(apiSuccessEnvelopeSchema.safeParse({ ok: true, data: {} }).success).toBe(false);
    expect(
      apiErrorEnvelopeSchema.safeParse({
        ok: false,
        errorCode: 'DEMO-SCENARIO-001',
        message: 'invalid request',
      }).success,
    ).toBe(false);
  });

  it('DO-015 只接受批准字段且 API-023 只接受严格命令联合', () => {
    const candidateExports = contractExports as typeof contractExports & {
      do015Schema?: {
        parse: (value: unknown) => unknown;
        safeParse: (value: unknown) => { success: boolean };
      };
      api023RequestSchema?: {
        safeParse: (value: unknown) => { success: boolean };
      };
    };
    const configSchema = candidateExports.do015Schema;
    const requestSchema = candidateExports.api023RequestSchema;

    expect(configSchema, 'DO-015 ConfigVersion schema').toBeDefined();
    expect(requestSchema, 'API023Request command union').toBeDefined();
    if (!configSchema || !requestSchema) return;

    const config = fixtures.objects['DO-015'][0];
    expect(configSchema.parse(config)).toEqual(config);
    expect(configSchema.safeParse({ ...config, areaCode: 'AREA-A' }).success).toBe(false);
    expect(configSchema.safeParse({ ...config, auditRetentionDays: 0 }).success).toBe(false);
    expect(configSchema.safeParse({ ...config, dispatchStrategy: 'RANDOM' }).success).toBe(false);

    expect(requestSchema.safeParse({
      command: 'edit',
      expectedVersion: 1,
      changes: { displayName: '新的 Demo 名称', offlineSyncEnabled: false },
    }).success).toBe(true);
    expect(requestSchema.safeParse({
      command: 'edit', expectedVersion: 1, changes: {},
    }).success).toBe(false);
    expect(requestSchema.safeParse({
      command: 'edit', expectedVersion: 1, changes: { areaCode: 'AREA-A' },
    }).success).toBe(false);
    expect(requestSchema.safeParse({
      command: 'submit', expectedVersion: 1, changes: { displayName: 'forbidden' },
    }).success).toBe(false);
    expect(requestSchema.safeParse({
      command: 'publish', expectedVersion: 1,
    }).success).toBe(true);
  });
});

describe('C02 请求契约', () => {
  const openapi = readJson<OpenApiDocument>('openapi.yaml');
  const requestExamples = Object.values(openapi.paths).flatMap((pathItem) =>
    Object.values(pathItem).flatMap((operation) => {
      const json = operation.requestBody?.content['application/json'];
      if (!json) return [];
      return [[json.schema.$ref.split('/').at(-1) as string, json.example] as const];
    }),
  );

  it('具名请求集合与 OpenAPI 一致且示例全部通过', () => {
    expect(Object.keys(requestSchemas).sort()).toEqual(requestExamples.map(([name]) => name).sort());
    expect(requestExamples).toHaveLength(14);

    for (const [name, example] of requestExamples) {
      expect(requestSchemas[name as keyof typeof requestSchemas].safeParse(example).success, name).toBe(true);
    }
  });

  it('reason 为可选字段，ruleVersion 为 RULE-1.0 字符串', () => {
    for (const [name, example] of requestExamples) {
      if ('reason' in example) {
        const { reason: _reason, ...withoutReason } = example;
        expect(requestSchemas[name as keyof typeof requestSchemas].safeParse(withoutReason).success, name).toBe(true);
      }
    }

    expect(requestSchemas.API007Request.safeParse({ ruleVersion: 'RULE-1.0', mode: 'AUTO' }).success).toBe(true);
    expect(requestSchemas.API007Request.safeParse({ ruleVersion: 1, mode: 'AUTO' }).success).toBe(false);
  });
});
