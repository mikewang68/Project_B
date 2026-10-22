import { inject, provide, type InjectionKey, type Ref } from "vue";
import type { ApiRecord } from "../../shared/types";

const reads = new Set([
  "trust:event:read", "trust:task:read", "trust:operations:read",
  "trust:evidence:read", "trust:evidence:verify", "trust:evidence:export",
]);
const edits = new Set([
  "trust:event:submit", "trust:event:correct", "trust:evidence:upload", "trust:task:retry",
]);

// IAM authorities are granular; compatibility ROLE_* values never grant UI actions.
// Local development users retain only the legacy controller capabilities.
export function hasPermission(user: ApiRecord | null, permission: string): boolean {
  if (!user) return false;
  const roles: string[] = Array.isArray(user.roles) ? user.roles : [];
  if (user.readOnly === true && ![
    "trust:event:read", "trust:task:read", "trust:operations:read", "trust:audit:read",
    "trust:wallet:read", "trust:identity:read",
  ].includes(permission)) return false;
  if (user.identityProvider === "IAM") return roles.includes(permission);
  if (user.identityProvider !== "LOCAL") return false;
  if (reads.has(permission)) return true;
  if (permission === "trust:audit:read") return roles.includes("ROLE_ADMIN");
  return edits.has(permission) && roles.some(r => ["ROLE_ADMIN", "ROLE_EDITOR"].includes(r));
}

export function canOpenPage(user: ApiRecord | null, page: string): boolean {
  const permissions: Record<string, string[]> = {
    events: ["trust:event:read"], trace: ["trust:event:read"],
    create: ["trust:event:submit", "trust:event:correct"], tasks: ["trust:task:read"],
    status: ["trust:operations:read"], wallets: ["trust:wallet:read"],
    identities: ["trust:identity:read"],
  };
  return (permissions[page] || []).some(p => hasPermission(user, p));
}

type Access = (permission: string) => boolean;
const key: InjectionKey<Access> = Symbol("trust-access");
export function provideAccess(user: Ref<ApiRecord | null>): Access {
  const allowed: Access = p => hasPermission(user.value, p);
  provide(key, allowed);
  return allowed;
}
export function useAccess(): Access {
  return inject(key, () => false);
}
