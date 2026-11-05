/** Pure preflight: no database, HTTP, IAM SDK or worker is loaded. */
export function assertLegacyBackgroundConfiguration(
  family: 'RAILWAY' | 'SIMULATOR', source: NodeJS.ProcessEnv = process.env,
): void {
  const prefix = `PSMS_${family}_`;
  const mode = source[prefix + 'AUTH_MODE'] ?? 'LEGACY_ONLY';
  if (!['LEGACY_ONLY', 'IAM_V2'].includes(mode)) throw new Error('BACKGROUND_AUTH_MODE_INVALID');
  if (mode === 'IAM_V2') throw new Error('BACKGROUND_V2_NOT_ADAPTED');
  const region = source[prefix + 'REGION_ID'] ?? '';
  const project = source[prefix + 'PROJECT_ID'] ?? '';
  if (source[prefix + 'SUBJECT_ID'] || source[prefix + 'SESSION_ID']
      || region !== '' && region !== 'UNASSIGNED'
      || project !== '' && project !== 'LEGACY')
    throw new Error('BACKGROUND_LEGACY_SCOPE_CONFLICT');
}
