import { Link } from 'react-router-dom';

import type { PermissionCode, SessionContext } from '../auth';
import { createC03Element } from '../app/routeCatalog';

export type ForbiddenStateProps = {
  requiredPermission: PermissionCode;
  session: SessionContext;
  firstAllowedPath: string;
};

export default function ForbiddenState({
  requiredPermission,
  session,
  firstAllowedPath,
}: ForbiddenStateProps) {
  return createC03Element(
    'section',
    { className: 'forbidden-state', 'aria-labelledby': 'forbidden-heading' },
    createC03Element('h2', { id: 'forbidden-heading' }, '403 无权访问'),
    createC03Element('p', null, `所需权限：${requiredPermission}`),
    createC03Element('p', null, `当前数据域：${session.dataScope.join(', ') || '无'}`),
    createC03Element(Link, { to: firstAllowedPath }, '前往首个可访问页面'),
  );
}
