import { centralContext } from '../middleware/central-scope.js';
import { assertLegacyBackgroundConfiguration } from './background-config-policy.js';

/** Configuration is not an IAM grant. These legacy workers have no V2 adapter yet. */
export function assertLegacyBackground(
  family: 'RAILWAY' | 'SIMULATOR', source: NodeJS.ProcessEnv = process.env,
): void {
  assertLegacyBackgroundConfiguration(family, source);
  if (centralContext()) throw new Error('BACKGROUND_REQUEST_CONTEXT_REJECTED');
}
