import type { PoolClient } from 'pg';
export function attachObservations<T extends { id: string; work_area?: string }>(
  db: Pick<PoolClient, 'query'>, equipment: T[],
): Promise<T[]>;
