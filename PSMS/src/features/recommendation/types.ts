import type { Plan, PublicErrorCode, Track } from '../../contracts';
import type {
  RecommendationCandidate,
  RecommendationExclusion,
} from './schemas';

export type RecommendationCalculationInput = Readonly<{
  plan: Plan;
  tracks: readonly Track[];
  generatedAt: string;
}>;

export type RecommendationCalculation = Readonly<{
  candidates: readonly RecommendationCandidate[];
  excluded: readonly RecommendationExclusion[];
}>;

export type TrackConflictViewModel = Readonly<{
  trackId: string;
  trackNo: string;
  exclusionCode: RecommendationExclusion['exclusionCode'];
  reason: string;
  sourceRefs: readonly string[];
}>;

export type RecommendationWorkflowState = Readonly<{
  selectedCandidateId?: string;
  adjustmentDrawerOpen: boolean;
  ruleDrawerOpen: boolean;
  lastCommandError?: Readonly<{ errorCode: PublicErrorCode; message: string }>;
}>;

export type RecommendationWorkflowStore = {
  getState: () => RecommendationWorkflowState;
  subscribe: (listener: () => void) => () => void;
  selectCandidate: (candidateId?: string) => void;
  setAdjustmentDrawerOpen: (open: boolean) => void;
  setRuleDrawerOpen: (open: boolean) => void;
  recordCommandError: (error?: { errorCode: PublicErrorCode; message: string }) => void;
  reset: () => void;
};
