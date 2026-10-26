import { authorizePage, loadCurrentDemoSession, type SessionContext } from '../auth';
import ForbiddenState from '../components/ForbiddenState';
import { createC03Element, routeCatalog, type RouteCatalogItem } from './routeCatalog';
import { Alert } from 'antd';
import BusinessDataPage from '../real/BusinessDataPage';
import { liveBusinessPages, type LiveBusinessPageId } from '../real/business';

export type RoutePermissionBoundaryProps = {
  route: RouteCatalogItem;
  session?: SessionContext;
  children?: unknown;
};

export default function RoutePermissionBoundary({
  route,
  session: suppliedSession,
  children,
}: RoutePermissionBoundaryProps) {
  const session = suppliedSession ?? loadCurrentDemoSession();
  const decision = authorizePage(session, route.id);
  if (decision.allow && import.meta.env.VITE_PSMS_REAL_API === 'true' && Object.hasOwn(liveBusinessPages, route.id))
    return createC03Element(BusinessDataPage, {pageId:route.id as LiveBusinessPageId});
  if (decision.allow && import.meta.env.VITE_PSMS_REAL_API === 'true' && !['UI-001','UI-002'].includes(route.id))
    return createC03Element(Alert, {type:'info', title:route.name+' · 待接入',
      description:'该功能保留原有页面，业务数据与操作接口尚未完成当前版本验收，暂不可启动。'});
  if (decision.allow) return children;

  const firstAllowedPath =
    routeCatalog.find((candidate) => authorizePage(session, candidate.id).allow)?.smokePath ?? '/';
  return createC03Element(ForbiddenState, {
    requiredPermission: route.requiredPermission,
    session,
    firstAllowedPath,
  });
}
