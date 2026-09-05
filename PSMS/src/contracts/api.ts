import type { RequestSchemaName } from './requests';

export type ApiMethod = 'get' | 'post';
export const apiIds = [
  'API-001',
  'API-002',
  'API-003',
  'API-004',
  'API-005',
  'API-006',
  'API-007',
  'API-008',
  'API-009',
  'API-010',
  'API-011',
  'API-012',
  'API-013',
  'API-014',
  'API-015',
  'API-016',
  'API-017',
  'API-018',
  'API-019',
  'API-020',
  'API-021',
  'API-022',
  'API-023',
  'API-024',
  'API-025',
] as const;
export type ApiId = (typeof apiIds)[number];

export type ApiParameter = {
  name: string;
  in: 'path' | 'query';
  required: boolean;
  type: string;
};

export type ApiContract = {
  apiId: ApiId;
  method: ApiMethod;
  openapiPath: string;
  mswPath: string;
  operationId: string;
  parameters: readonly ApiParameter[];
  requestSchema?: RequestSchemaName;
  responseSchemas: {
    200: 'ApiSuccessEnvelope' | 'API022SuccessEnvelope' | 'API023SuccessEnvelope';
    400: 'ApiErrorEnvelope';
    403: 'ApiErrorEnvelope';
    409: 'ApiErrorEnvelope';
  };
};

const responses = {
  200: 'ApiSuccessEnvelope',
  400: 'ApiErrorEnvelope',
  403: 'ApiErrorEnvelope',
  409: 'ApiErrorEnvelope',
} as const;

const api022Responses = { ...responses, 200: 'API022SuccessEnvelope' } as const;
const api023Responses = { ...responses, 200: 'API023SuccessEnvelope' } as const;

const pathId = [{ name: 'id', in: 'path', required: true, type: 'string' }] as const;

export const apiCatalog: readonly ApiContract[] = [
  {
    apiId: 'API-001', method: 'get', openapiPath: '/mock/overview', mswPath: '/mock/overview', operationId: 'GET_mock_overview',
    parameters: [
      { name: 'date', in: 'query', required: false, type: 'string' },
      { name: 'workArea', in: 'query', required: false, type: 'string' },
    ], responseSchemas: responses,
  },
  { apiId: 'API-002', method: 'get', openapiPath: '/mock/plans', mswPath: '/mock/plans', operationId: 'GET_mock_plans', parameters: [], responseSchemas: responses },
  { apiId: 'API-003', method: 'post', openapiPath: '/mock/plans/sync', mswPath: '/mock/plans/sync', operationId: 'POST_mock_plans_sync', parameters: [], requestSchema: 'API003Request', responseSchemas: responses },
  { apiId: 'API-004', method: 'post', openapiPath: '/mock/plans/{id}/confirm', mswPath: '/mock/plans/:id/confirm', operationId: 'POST_mock_plans_id_confirm', parameters: pathId, requestSchema: 'API004Request', responseSchemas: responses },
  { apiId: 'API-005', method: 'get', openapiPath: '/mock/plans/{id}/recommendation', mswPath: '/mock/plans/:id/recommendation', operationId: 'GET_mock_plans_id_recommendation', parameters: [...pathId, { name: 'inputVersion', in: 'query', required: false, type: 'integer' }], responseSchemas: responses },
  { apiId: 'API-006', method: 'post', openapiPath: '/mock/plans/{id}/recommendation/confirm', mswPath: '/mock/plans/:id/recommendation/confirm', operationId: 'POST_mock_plans_id_recommendation_confirm', parameters: pathId, requestSchema: 'API006Request', responseSchemas: responses },
  { apiId: 'API-007', method: 'post', openapiPath: '/mock/plans/{id}/decompose', mswPath: '/mock/plans/:id/decompose', operationId: 'POST_mock_plans_id_decompose', parameters: pathId, requestSchema: 'API007Request', responseSchemas: responses },
  { apiId: 'API-008', method: 'post', openapiPath: '/mock/work-orders/{id}/assign', mswPath: '/mock/work-orders/:id/assign', operationId: 'POST_mock_work_orders_id_assign', parameters: pathId, requestSchema: 'API008Request', responseSchemas: responses },
  { apiId: 'API-009', method: 'post', openapiPath: '/mock/work-orders/{id}/dispatch', mswPath: '/mock/work-orders/:id/dispatch', operationId: 'POST_mock_work_orders_id_dispatch', parameters: pathId, requestSchema: 'API009Request', responseSchemas: responses },
  { apiId: 'API-010', method: 'get', openapiPath: '/mock/appointments', mswPath: '/mock/appointments', operationId: 'GET_mock_appointments', parameters: [{ name: 'role', in: 'query', required: false, type: 'string' }], responseSchemas: responses },
  { apiId: 'API-011', method: 'post', openapiPath: '/mock/appointments/{id}/transition', mswPath: '/mock/appointments/:id/transition', operationId: 'POST_mock_appointments_id_transition', parameters: pathId, requestSchema: 'API011Request', responseSchemas: responses },
  { apiId: 'API-012', method: 'get', openapiPath: '/mock/operations', mswPath: '/mock/operations', operationId: 'GET_mock_operations', parameters: [{ name: 'time', in: 'query', required: false, type: 'string' }], responseSchemas: responses },
  { apiId: 'API-013', method: 'post', openapiPath: '/mock/scenarios/{id}/play', mswPath: '/mock/scenarios/:id/play', operationId: 'POST_mock_scenarios_id_play', parameters: pathId, requestSchema: 'API013Request', responseSchemas: responses },
  { apiId: 'API-014', method: 'get', openapiPath: '/mock/exceptions', mswPath: '/mock/exceptions', operationId: 'GET_mock_exceptions', parameters: [], responseSchemas: responses },
  { apiId: 'API-015', method: 'post', openapiPath: '/mock/exceptions/{id}/command', mswPath: '/mock/exceptions/:id/command', operationId: 'POST_mock_exceptions_id_command', parameters: pathId, requestSchema: 'API015Request', responseSchemas: responses },
  { apiId: 'API-016', method: 'get', openapiPath: '/mock/interlocks', mswPath: '/mock/interlocks', operationId: 'GET_mock_interlocks', parameters: [], responseSchemas: responses },
  { apiId: 'API-017', method: 'post', openapiPath: '/mock/interlocks/{id}/command', mswPath: '/mock/interlocks/:id/command', operationId: 'POST_mock_interlocks_id_command', parameters: pathId, requestSchema: 'API017Request', responseSchemas: responses },
  { apiId: 'API-018', method: 'get', openapiPath: '/mock/offline-packets', mswPath: '/mock/offline-packets', operationId: 'GET_mock_offline_packets', parameters: [], responseSchemas: responses },
  { apiId: 'API-019', method: 'post', openapiPath: '/mock/offline-packets/{id}/command', mswPath: '/mock/offline-packets/:id/command', operationId: 'POST_mock_offline_packets_id_command', parameters: pathId, requestSchema: 'API019Request', responseSchemas: responses },
  { apiId: 'API-020', method: 'get', openapiPath: '/mock/reports', mswPath: '/mock/reports', operationId: 'GET_mock_reports', parameters: [
    { name: 'type', in: 'query', required: false, type: 'string' },
    { name: 'period', in: 'query', required: false, type: 'string' },
    { name: 'dimensions', in: 'query', required: false, type: 'array' },
  ], responseSchemas: responses },
  { apiId: 'API-021', method: 'post', openapiPath: '/mock/reports/export', mswPath: '/mock/reports/export', operationId: 'POST_mock_reports_export', parameters: [], requestSchema: 'API021Request', responseSchemas: responses },
  { apiId: 'API-022', method: 'get', openapiPath: '/mock/config', mswPath: '/mock/config', operationId: 'GET_mock_config', parameters: [{ name: 'domain', in: 'query', required: false, type: 'string' }], responseSchemas: api022Responses },
  { apiId: 'API-023', method: 'post', openapiPath: '/mock/config/{id}/command', mswPath: '/mock/config/:id/command', operationId: 'POST_mock_config_id_command', parameters: pathId, requestSchema: 'API023Request', responseSchemas: api023Responses },
  { apiId: 'API-024', method: 'get', openapiPath: '/mock/audit-logs', mswPath: '/mock/audit-logs', operationId: 'GET_mock_audit_logs', parameters: [], responseSchemas: responses },
  { apiId: 'API-025', method: 'post', openapiPath: '/mock/demo/reset', mswPath: '/mock/demo/reset', operationId: 'POST_mock_demo_reset', parameters: [], requestSchema: 'API025Request', responseSchemas: responses },
];
