/** Optional service-manager preflight for older workers with no IAM SDK installed. */
import 'dotenv/config';
import { assertLegacyBackgroundConfiguration } from '../services/background-config-policy.js';

const family = process.argv[2];
if (family !== 'RAILWAY' && family !== 'SIMULATOR') {
  console.error('BACKGROUND_FAMILY_INVALID'); process.exitCode = 1;
} else {
  try {
    assertLegacyBackgroundConfiguration(family);
    console.log(JSON.stringify({status:'PASS',family,authMode:'LEGACY_ONLY',businessExecuted:false}));
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'BACKGROUND_PREFLIGHT_REJECTED');
    process.exitCode = 1;
  }
}
