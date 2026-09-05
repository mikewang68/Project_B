import { authorizePage, loadCurrentDemoSession, type SessionContext } from '../auth';
import ForbiddenState from '../components/ForbiddenState';
import { createC03Element, routeCatalog, type RouteCatalogItem } from './routeCatalog';

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
  if (decision.allow) return children;

  const firstAllowedPath =
    routeCatalog.find((candidate) => authorizePage(session, candidate.id).allow)?.smokePath ?? '/';
  return createC03Element(ForbiddenState, {
    requiredPermission: route.requiredPermission,
    session,
    firstAllowedPath,
  });
}
