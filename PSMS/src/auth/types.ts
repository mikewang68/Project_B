import { roleCodes } from '../contracts';
import type { PermissionCode } from './permissionCatalog';

export type RoleCode = (typeof roleCodes)[number];

export type PageId =
  | 'UI-001'
  | 'UI-002'
  | 'UI-003'
  | 'UI-004'
  | 'UI-005'
  | 'UI-006'
  | 'UI-007'
  | 'UI-008'
  | 'UI-009'
  | 'UI-010'
  | 'UI-011'
  | 'UI-012'
  | 'UI-013';

export type SessionContext = {
  actorId: string;
  roleCode: RoleCode;
  dataScope: readonly string[];
  online: boolean;
  demoTime: string;
};

export type DataScopeDescriptor = {
  type: string;
  value?: string;
  ownerId?: string;
};

export type PolicyContext = {
  session: SessionContext;
  pageId?: PageId | string;
  permission: PermissionCode | string;
  objectScope?: DataScopeDescriptor;
  applicantId?: string;
  approverId?: string;
  highRisk?: boolean;
  permissionElevation?: boolean;
  mutationTarget?: 'BUSINESS' | 'AUDIT';
  expectedVersion?: number;
  actualVersion?: number;
};

export type PermissionDecision =
  | { allow: true }
  | {
      allow: false;
      errorCode: 'TOS-AUTH-001' | 'DEMO-VERSION-001';
      stage: 'PAGE' | 'ACTION' | 'DATA_SCOPE' | 'SEPARATION_OF_DUTIES' | 'ONLINE' | 'VERSION';
      reason: string;
    };
