import { z } from 'zod';
import { tonUnits, validateDispatchDirection, workOrderDispatchDetails } from './wms-contract.js';

export const railwayImportLimit = 64 * 1024;
export const railwayImportSchema = z.object({
  version: z.literal('psms.railway-dispatch.v1'),
  externalOrderNo: z.string().trim().min(1).max(100),
  planId: z.string().trim().min(1).max(160),
  orderType: z.enum(['LOADING', 'UNLOADING']),
  priority: z.enum(['HIGH', 'MEDIUM', 'LOW']),
  description: z.string().trim().min(1).max(1000),
  dispatchDetails: workOrderDispatchDetails,
}).strict().superRefine(({ orderType, dispatchDetails }, ctx) => {
  const issue = validateDispatchDirection(orderType, dispatchDetails);
  if (issue) ctx.addIssue({ code: 'custom', path: ['dispatchDetails'], message: issue });
});

export function requiresTonUnits(cargoType: string) {
  return ['FLY_ASH', 'STEEL', '粉煤灰', '钢材'].includes(cargoType);
}
export function allLinesUseTons(details: z.infer<typeof workOrderDispatchDetails>) {
  return details.lines.every(line => tonUnits.includes(line.unit));
}

export type RailwayImportSource = { externalOrderNo: string; channel: 'FILE' | 'EMAIL'; filename: string; sender?: string };