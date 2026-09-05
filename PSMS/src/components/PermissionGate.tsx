import { authorize, type PermissionCode, type PolicyContext } from '../auth';
import {
  cloneC03Element,
  createC03Element,
  isC03Element,
} from '../app/routeCatalog';

export type PermissionGateProps = {
  permission: PermissionCode;
  context: PolicyContext;
  mode: 'hide' | 'disable' | 'explain';
  children: unknown;
};

function disabledChild(children: unknown, reason: string) {
  if (!isC03Element(children)) {
    return createC03Element('span', { 'aria-disabled': true, title: reason }, children);
  }

  return cloneC03Element(children, {
    disabled: true,
    'aria-disabled': true,
    title: reason,
  });
}

export default function PermissionGate({
  permission,
  context,
  mode,
  children,
}: PermissionGateProps) {
  const decision = authorize({ ...context, permission });
  if (decision.allow) return children;
  if (mode === 'hide') return null;

  const disabled = disabledChild(children, decision.reason);
  if (mode === 'disable') return disabled;

  return createC03Element(
    'span',
    { className: 'permission-gate-explanation' },
    disabled,
    createC03Element('span', { role: 'note' }, decision.reason),
  );
}
