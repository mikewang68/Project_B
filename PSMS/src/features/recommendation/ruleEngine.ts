import type { Track } from '../../contracts';
import {
  recommendationCandidateSchema,
  recommendationExclusionSchema,
  type RecommendationCandidate,
  type RecommendationExclusion,
} from './schemas';
import type { RecommendationCalculation, RecommendationCalculationInput } from './types';

const eligibleStatuses = ['FREE', 'OCCUPIED', 'RELEASING'] as const;
type EligibleStatus = (typeof eligibleStatuses)[number];

const availabilityByStatus: Record<EligibleStatus, number> = {
  FREE: 45,
  RELEASING: 35,
  OCCUPIED: 25,
};

const exclusionReasons = {
  TRACK_BLOCKED: '股道处于封锁状态',
  CARGO_INCOMPATIBLE: '股道不兼容计划货类',
  UNSUPPORTED_OCCUPANCY: '股道占用状态不受推荐规则支持',
  INVALID_RELEASE_TIME: '股道预计释放时间无效',
} as const;

function deepFreeze<T>(value: T): T {
  if (typeof value !== 'object' || value === null || Object.isFrozen(value)) return value;
  for (const nested of Object.values(value as Record<string, unknown>)) deepFreeze(nested);
  return Object.freeze(value);
}

function parseTimestamp(value: string, label: string): number {
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid ${label}: ${value}`);
  return parsed;
}

function offsetSuffix(value: string): string {
  const match = value.match(/(Z|[+-]\d{2}:\d{2})$/);
  if (!match) throw new Error(`Timestamp must include an ISO offset: ${value}`);
  return match[1]!;
}

function formatLike(valueMs: number, template: string): string {
  const suffix = offsetSuffix(template);
  const includeMilliseconds = /\.\d{3}(?:Z|[+-]\d{2}:\d{2})$/.test(template);
  if (suffix === 'Z') {
    const iso = new Date(valueMs).toISOString();
    return includeMilliseconds ? iso : `${iso.slice(0, 19)}Z`;
  }

  const sign = suffix.startsWith('-') ? -1 : 1;
  const [hours, minutes] = suffix.slice(1).split(':').map(Number);
  const offsetMinutes = sign * (hours! * 60 + minutes!);
  const shifted = new Date(valueMs + offsetMinutes * 60_000).toISOString();
  const localPart = includeMilliseconds ? shifted.slice(0, 23) : shifted.slice(0, 19);
  return `${localPart}${suffix}`;
}

function exclusion(
  planId: string,
  track: Track,
  exclusionCode: keyof typeof exclusionReasons,
): RecommendationExclusion {
  return recommendationExclusionSchema.parse({
    trackId: track.id,
    trackNo: track.trackNo,
    exclusionCode,
    reason: exclusionReasons[exclusionCode],
    sourceRefs: [`PLAN:${planId}`, `TRACK:${track.id}`],
  });
}

function isEligibleStatus(value: string): value is EligibleStatus {
  return eligibleStatuses.some((status) => status === value);
}

export function calculateReceptionRecommendation(
  input: RecommendationCalculationInput,
): RecommendationCalculation {
  parseTimestamp(input.generatedAt, 'generatedAt');

  const intervalParts = input.plan.arrivalDepartureTime.split('/');
  if (intervalParts.length !== 2) throw new Error('Plan arrival/departure interval is invalid.');
  const arrivalText = intervalParts[0]!;
  const departureText = intervalParts[1]!;
  const arrivalMs = parseTimestamp(arrivalText, 'plan arrival time');
  const departureMs = parseTimestamp(departureText, 'plan departure time');
  const durationMs = departureMs - arrivalMs;
  if (durationMs <= 0) throw new Error('Plan duration must be positive.');
  offsetSuffix(arrivalText);
  offsetSuffix(departureText);

  const rawCandidates: Array<Omit<RecommendationCandidate, 'rank' | 'recommended'>> = [];
  const excluded: RecommendationExclusion[] = [];

  for (const track of input.tracks) {
    if (track.occupyStatus === 'BLOCKED') {
      excluded.push(exclusion(input.plan.id, track, 'TRACK_BLOCKED'));
      continue;
    }
    if (!track.compatibleCargoTypes.includes(input.plan.cargoType)) {
      excluded.push(exclusion(input.plan.id, track, 'CARGO_INCOMPATIBLE'));
      continue;
    }
    if (!isEligibleStatus(track.occupyStatus)) {
      excluded.push(exclusion(input.plan.id, track, 'UNSUPPORTED_OCCUPANCY'));
      continue;
    }

    const releaseMs =
      track.occupyStatus === 'FREE' ? arrivalMs : Date.parse(track.estimateReleaseTime);
    if (track.occupyStatus !== 'FREE' && !Number.isFinite(releaseMs)) {
      excluded.push(exclusion(input.plan.id, track, 'INVALID_RELEASE_TIME'));
      continue;
    }

    const candidateStartMs =
      track.occupyStatus === 'FREE' ? arrivalMs : Math.max(arrivalMs, releaseMs);
    const windowStart = formatLike(candidateStartMs, arrivalText);
    const windowEnd = formatLike(candidateStartMs + durationMs, arrivalText);
    const delayMinutes = Math.max(0, Math.floor((candidateStartMs - arrivalMs) / 60_000));
    const timing = Math.max(0, 30 - Math.floor(delayMinutes / 10));
    const availability = availabilityByStatus[track.occupyStatus];
    const continuity = track.trackNo === input.plan.trackNo ? 15 : 5;
    const authority =
      input.plan.sourceSystem === 'RAIL_PLAN' && input.plan.sourceTime && track.updatedAt ? 10 : 0;
    const scoreBreakdown = { availability, timing, continuity, authority };
    const score = availability + timing + continuity + authority;

    rawCandidates.push({
      candidateId: `${input.plan.id}:${track.id}:${windowStart}`,
      trackId: track.id,
      trackNo: track.trackNo,
      trackVersion: track.version,
      occupyStatus: track.occupyStatus,
      windowStart,
      windowEnd,
      score,
      scoreBreakdown,
      reasons: [
        track.occupyStatus === 'FREE' ? '股道当前空闲' : `股道预计于 ${track.estimateReleaseTime} 释放`,
        delayMinutes === 0 ? '匹配计划到达时间' : `需错峰 ${delayMinutes} 分钟`,
        continuity === 15 ? '保持原计划股道连续性' : '切换至其他兼容股道',
        authority === 10 ? '计划与股道权威时间完整' : '权威时间信息不完整',
      ],
      sourceRefs: [`PLAN:${input.plan.id}`, `TRACK:${track.id}`],
    });
  }

  rawCandidates.sort(
    (left, right) =>
      right.score - left.score ||
      left.windowStart.localeCompare(right.windowStart) ||
      left.trackNo.localeCompare(right.trackNo),
  );

  const candidates = rawCandidates.map((candidate, index) =>
    recommendationCandidateSchema.parse({
      ...candidate,
      rank: index + 1,
      recommended: index === 0,
    }),
  );
  excluded.sort((left, right) => left.trackNo.localeCompare(right.trackNo));

  return deepFreeze({ candidates, excluded });
}
