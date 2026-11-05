/** Deployment-owned address; never accept a redirect URL from the query string. */
export function trustedManagementUrl(value: string | undefined): string | null {
  if (!value?.trim()) return null
  try {
    const url = new URL(value.trim())
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch { return null }
}

export const centralManagementUrl = trustedManagementUrl(import.meta.env.VITE_IAM_MANAGEMENT_URL)
