import { z } from 'zod';
import { roleCodeSchema } from '../../contracts';

export const RECOMMENDATION_RULE_VERSION = 'C05-DEMO-RULE-1.0' as const;
export const recommendationStatuses = ['CALCULATED', 'CONFIRMED'] as const;
export const exclusionCodes = [
  'TRACK_BLOCKED',
  'CARGO_INCOMPATIBLE',
  'UNSUPPORTED_OCCUPANCY',
  'INVALID_RELEASE_TIME',
] as const;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

const nonEmptyStringSchema = z.string().trim().min(1);
const nonEmptyStringArraySchema = z.array(nonEmptyStringSchema).min(1);

export const recommendationScoreBreakdownSchema = z
  .object({
    availability: z.number().int().min(0).max(45),
    timing: z.number().int().min(0).max(30),
    continuity: z.number().int().min(0).max(15),
    authority: z.number().int().min(0).max(10),
  })
  .strict();

export const recommendationCandidateSchema = z
  .object({
    candidateId: nonEmptyStringSchema,
    trackId: nonEmptyStringSchema,
    trackNo: nonEmptyStringSchema,
    trackVersion: z.number().int().positive(),
    occupyStatus: z.enum(['FREE', 'OCCUPIED', 'RELEASING']),
    windowStart: nonEmptyStringSchema,
    windowEnd: nonEmptyStringSchema,
    score: z.number().int().min(0).max(100),
    rank: z.number().int().positive(),
    recommended: z.boolean(),
    scoreBreakdown: recommendationScoreBreakdownSchema,
    reasons: nonEmptyStringArraySchema,
    sourceRefs: nonEmptyStringArraySchema,
  })
  .strict()
  .superRefine((candidate, context) => {
    const total = Object.values(candidate.scoreBreakdown).reduce((sum, value) => sum + value, 0);
    if (candidate.score !== total) {
      context.addIssue({
        code: 'custom',
        path: ['score'],
        message: 'Score must equal the score breakdown total.',
      });
    }
    if (candidate.recommended !== (candidate.rank === 1)) {
      context.addIssue({
        code: 'custom',
        path: ['recommended'],
        message: 'Only the first-ranked candidate may be recommended.',
      });
    }
  })
  .transform(deepFreeze);

export const recommendationExclusionSchema = z
  .object({
    trackId: nonEmptyStringSchema,
    trackNo: nonEmptyStringSchema,
    exclusionCode: z.enum(exclusionCodes),
    reason: nonEmptyStringSchema,
    sourceRefs: nonEmptyStringArraySchema,
  })
  .strict()
  .transform(deepFreeze);

export const recommendationAdjustmentSchema = z
  .object({
    originalCandidateId: nonEmptyStringSchema,
    finalCandidateId: nonEmptyStringSchema,
    reason: nonEmptyStringSchema,
    affectedWorkOrderIds: nonEmptyStringArraySchema,
    reviewerId: nonEmptyStringSchema.optional(),
  })
  .strict();

export const recommendationConfirmationSchema = z
  .object({
    actorId: nonEmptyStringSchema,
    roleCode: roleCodeSchema,
    confirmedAt: nonEmptyStringSchema,
    commandId: nonEmptyStringSchema,
    traceId: nonEmptyStringSchema,
  })
  .strict();

export const recommendationDraftSchema = z
  .object({
    planId: nonEmptyStringSchema,
    draftVersion: z.number().int().positive(),
    status: z.enum(recommendationStatuses),
    inputPlanVersion: z.number().int().positive(),
    ruleVersion: z.literal(RECOMMENDATION_RULE_VERSION),
    generatedAt: nonEmptyStringSchema,
    candidates: z.array(recommendationCandidateSchema),
    excluded: z.array(recommendationExclusionSchema),
    selectedCandidateId: nonEmptyStringSchema.optional(),
    adjustment: recommendationAdjustmentSchema.optional(),
    confirmation: recommendationConfirmationSchema.optional(),
  })
  .strict()
  .superRefine((draft, context) => {
    const candidateIds = new Set(draft.candidates.map(({ candidateId }) => candidateId));
    if (draft.selectedCandidateId && !candidateIds.has(draft.selectedCandidateId)) {
      context.addIssue({
        code: 'custom',
        path: ['selectedCandidateId'],
        message: 'Selected candidate must exist in candidates.',
      });
    }
    if (draft.status === 'CONFIRMED' && !draft.selectedCandidateId) {
      context.addIssue({
        code: 'custom',
        path: ['selectedCandidateId'],
        message: 'A confirmed draft requires a selected candidate.',
      });
    }
    if (draft.status === 'CONFIRMED' && !draft.confirmation) {
      context.addIssue({
        code: 'custom',
        path: ['confirmation'],
        message: 'A confirmed draft requires confirmation metadata.',
      });
    }
    if (draft.status === 'CALCULATED' && draft.confirmation) {
      context.addIssue({
        code: 'custom',
        path: ['confirmation'],
        message: 'A calculated draft cannot contain confirmation metadata.',
      });
    }
    if (draft.adjustment && draft.adjustment.finalCandidateId !== draft.selectedCandidateId) {
      context.addIssue({
        code: 'custom',
        path: ['adjustment', 'finalCandidateId'],
        message: 'Adjustment final candidate must equal the selected candidate.',
      });
    }
  })
  .transform(deepFreeze);

export type RecommendationCandidate = z.infer<typeof recommendationCandidateSchema>;
export type RecommendationExclusion = z.infer<typeof recommendationExclusionSchema>;
export type RecommendationAdjustment = z.infer<typeof recommendationAdjustmentSchema>;
export type RecommendationConfirmation = z.infer<typeof recommendationConfirmationSchema>;
export type RecommendationDraft = z.infer<typeof recommendationDraftSchema>;
