import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { apiCatalog, apiErrorEnvelopeSchema, apiSuccessEnvelopeSchema } from '../index';

type JsonSchemaRef = { $ref: string };

type OpenApiOperation = {
  operationId: string;
  'x-api-id': string;
  'x-msw-path': string;
  parameters?: Array<{
    name: string;
    in: 'path' | 'query';
    required?: boolean;
    schema: { type: string };
  }>;
  requestBody?: {
    content: { 'application/json': { schema: JsonSchemaRef; example: unknown } };
  };
  responses: Record<
    '200' | '400' | '403' | '409',
    { content: { 'application/json': { schema: JsonSchemaRef; example: unknown } } }
  >;
};

type OpenApiDocument = {
  paths: Record<string, Record<string, OpenApiOperation>>;
  components: { schemas: Record<string, unknown> };
};

function readOpenApi(): OpenApiDocument {
  return JSON.parse(
    readFileSync(join(process.cwd(), 'docs', 'baseline', 'openapi.yaml'), 'utf8'),
  ) as OpenApiDocument;
}

function schemaName(ref: string): string {
  return ref.split('/').at(-1) as string;
}

describe('C02 OpenAPI 与运行时 catalog 一致性', () => {
  const openapi = readOpenApi();
  const operations = Object.entries(openapi.paths).flatMap(([openapiPath, pathItem]) =>
    Object.entries(pathItem)
      .filter(([method]) => ['get', 'post', 'put', 'patch', 'delete'].includes(method))
      .map(([method, operation]) => ({ openapiPath, method, operation })),
  );

  it('恰好映射 25 个方法、标准路径、operationId 与 x-msw-path', () => {
    expect(operations).toHaveLength(25);
    expect(apiCatalog).toHaveLength(25);
    expect(new Set(apiCatalog.map(({ apiId }) => apiId)).size).toBe(25);
    expect(new Set(apiCatalog.map(({ operationId }) => operationId)).size).toBe(25);

    for (const { method, openapiPath, operation } of operations) {
      const runtime = apiCatalog.find(({ apiId }) => apiId === operation['x-api-id']);
      expect(runtime).toMatchObject({
        apiId: operation['x-api-id'],
        method,
        openapiPath,
        mswPath: operation['x-msw-path'],
        operationId: operation.operationId,
      });
      expect(openapiPath).not.toContain(':id');
      expect(operation['x-msw-path'].replace(':id', '{id}')).toBe(openapiPath);
    }
  });

  it('GET 只使用 query/path 参数，POST 具名请求与参数映射完整', () => {
    for (const { method, operation } of operations) {
      const runtime = apiCatalog.find(({ apiId }) => apiId === operation['x-api-id']);
      const expectedParameters = (operation.parameters ?? []).map((parameter) => ({
        name: parameter.name,
        in: parameter.in,
        required: parameter.required ?? false,
        type: parameter.schema.type,
      }));

      expect(runtime?.parameters).toEqual(expectedParameters);
      if (method === 'get') {
        expect(operation.requestBody).toBeUndefined();
        expect(runtime?.requestSchema).toBeUndefined();
      } else {
        expect(runtime?.requestSchema).toBe(
          schemaName(operation.requestBody?.content['application/json'].schema.$ref as string),
        );
      }
    }
  });

  it('每个操作引用可执行信封，API-022/023 使用精确成功 Schema', () => {
    for (const { operation } of operations) {
      const runtime = apiCatalog.find(({ apiId }) => apiId === operation['x-api-id']);
      const expectedSuccessSchema = operation['x-api-id'] === 'API-022'
        ? 'API022SuccessEnvelope'
        : operation['x-api-id'] === 'API-023'
          ? 'API023SuccessEnvelope'
          : 'ApiSuccessEnvelope';

      expect(runtime?.responseSchemas).toEqual({
        200: expectedSuccessSchema,
        400: 'ApiErrorEnvelope',
        403: 'ApiErrorEnvelope',
        409: 'ApiErrorEnvelope',
      });
      expect(schemaName(operation.responses['200'].content['application/json'].schema.$ref)).toBe(
        expectedSuccessSchema,
      );
      expect(
        apiSuccessEnvelopeSchema.safeParse(operation.responses['200'].content['application/json'].example).success,
        operation['x-api-id'],
      ).toBe(true);

      for (const status of ['400', '403', '409'] as const) {
        expect(schemaName(operation.responses[status].content['application/json'].schema.$ref)).toBe(
          'ApiErrorEnvelope',
        );
        expect(
          apiErrorEnvelopeSchema.safeParse(operation.responses[status].content['application/json'].example).success,
          `${operation['x-api-id']} ${status}`,
        ).toBe(true);
      }
    }
  });

  it('OpenAPI 中恰好有 15 个领域对象而非 15 个总组件', () => {
    expect(Object.keys(openapi.components.schemas).filter((name) => /^DO\d{3}$/.test(name))).toEqual(
      Array.from({ length: 15 }, (_, index) => `DO${String(index + 1).padStart(3, '0')}`),
    );
    expect(Object.keys(openapi.components.schemas).length).toBeGreaterThan(15);
  });

  it('API-023 是 edit/state command 联合且配置响应只引用 CFG-001', () => {
    const api023Request = openapi.components.schemas.API023Request as {
      oneOf?: unknown[];
    };
    const configOperations = operations.filter(({ operation }) =>
      operation['x-api-id'] === 'API-022' || operation['x-api-id'] === 'API-023');

    expect(api023Request.oneOf).toHaveLength(2);
    for (const { operation } of configOperations) {
      const example = operation.responses['200'].content['application/json'].example as {
        data?: { items?: Array<{ id?: string }> };
      };
      expect(example.data?.items).toEqual([expect.objectContaining({ id: 'CFG-001' })]);
    }
  });
});
