import { z } from 'zod';
import { do015Schema, jsonObjectSchema } from './schemas';

export const api003RequestSchema = z.object({ scenarioId: z.string() }).strict();
export const api004RequestSchema = z
  .object({ reason: z.string().optional(), supplements: jsonObjectSchema.optional() })
  .strict();
export const api006RequestSchema = z
  .object({ trackNo: z.string(), window: z.string(), reason: z.string().optional() })
  .strict();
export const api007RequestSchema = z.object({ ruleVersion: z.string(), mode: z.string() }).strict();
export const api008RequestSchema = z.object({ resourceId: z.string(), reason: z.string().optional() }).strict();
export const api009RequestSchema = z.object({ target: z.string(), simulateReceipt: z.boolean() }).strict();
export const api011RequestSchema = z.object({ action: z.string(), evidence: jsonObjectSchema }).strict();
export const api013RequestSchema = z.object({ speed: z.number() }).strict();
export const api015RequestSchema = z
  .object({ command: z.string(), reason: z.string().optional(), evidence: jsonObjectSchema })
  .strict();
export const api017RequestSchema = z
  .object({ command: z.string(), receipt: jsonObjectSchema, approval: jsonObjectSchema })
  .strict();
export const api019RequestSchema = z
  .object({ upload: z.string(), merge: z.string(), reject: z.string(), retry: z.string() })
  .strict();
export const api021RequestSchema = z
  .object({ scope: z.string(), format: z.string(), purpose: z.string() })
  .strict();
export const configVersionChangesSchema = do015Schema
  .pick({
    displayName: true,
    defaultScenarioId: true,
    ruleVersion: true,
    dispatchStrategy: true,
    recommendationEnabled: true,
    offlineSyncEnabled: true,
    reportPeriod: true,
    auditRetentionDays: true,
  })
  .partial()
  .strict()
  .refine((changes) => Object.keys(changes).length > 0, {
    message: 'edit changes must contain at least one approved field.',
  });

const configCommandBase = {
  expectedVersion: z.number().int().positive(),
  reason: z.string().trim().min(1).optional(),
};

export const api023RequestSchema = z.discriminatedUnion('command', [
  z
    .object({
      command: z.literal('edit'),
      ...configCommandBase,
      changes: configVersionChangesSchema,
    })
    .strict(),
  z
    .object({
      command: z.enum(['submit', 'approve', 'publish', 'rollback']),
      ...configCommandBase,
    })
    .strict(),
]);
export const api025RequestSchema = z.object({ scenarioId: z.string() }).strict();

export const requestSchemas = {
  API003Request: api003RequestSchema,
  API004Request: api004RequestSchema,
  API006Request: api006RequestSchema,
  API007Request: api007RequestSchema,
  API008Request: api008RequestSchema,
  API009Request: api009RequestSchema,
  API011Request: api011RequestSchema,
  API013Request: api013RequestSchema,
  API015Request: api015RequestSchema,
  API017Request: api017RequestSchema,
  API019Request: api019RequestSchema,
  API021Request: api021RequestSchema,
  API023Request: api023RequestSchema,
  API025Request: api025RequestSchema,
} as const;

export type RequestSchemaName = keyof typeof requestSchemas;
