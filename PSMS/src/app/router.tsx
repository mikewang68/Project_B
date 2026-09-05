import {
  Navigate,
  createBrowserRouter,
  type RouteObject,
} from 'react-router-dom';
import { authorizePage, loadCurrentDemoSession, type SessionContext } from '../auth';
import SkeletonLayout from '../layouts/SkeletonLayout';
import NotFoundPage from '../pages/NotFoundPage';
import RouteErrorPage from '../pages/RouteErrorPage';
import RoutePermissionBoundary from './RoutePermissionBoundary';
import {
  C03Suspense,
  createC03Element,
  createC03Lazy,
  routeCatalog,
  type RouteCatalogItem,
} from './routeCatalog';
import { appBasePath } from '../runtime/appBasePath';

export function createProtectedRouteElement(
  route: RouteCatalogItem,
  session?: SessionContext,
): ReturnType<typeof createC03Element> {
  const LazyPage = createC03Lazy(route.loadPage);
  return createC03Element(
    RoutePermissionBoundary,
    { route, session },
    createC03Element(
      C03Suspense,
      { fallback: createC03Element('div', { role: 'status' }, '正在加载页面…') },
      createC03Element(LazyPage, null),
    ),
  );
}

function DefaultRouteRedirect() {
  const session = loadCurrentDemoSession();
  const target =
    routeCatalog.find((route) => authorizePage(session, route.id).allow)?.smokePath ??
    '/not-a-real-route';
  return <Navigate to={target} replace />;
}

const businessRoutes: RouteObject[] = routeCatalog.map((route) => ({
  path: route.path.slice(1),
  element: createProtectedRouteElement(route),
}));

export const appRoutes: RouteObject[] = [
  {
    path: '/',
    element: <SkeletonLayout />,
    errorElement: <RouteErrorPage />,
    hydrateFallbackElement: (
      <div className="route-hydrate-fallback" role="status">
        正在加载页面框架…
      </div>
    ),
    children: [
      {
        index: true,
        element: <DefaultRouteRedirect />,
      },
      ...businessRoutes,
      {
        path: '*',
        element: <NotFoundPage />,
      },
    ],
  },
];

export const appRouter = createBrowserRouter(appRoutes, {
  basename: appBasePath || undefined,
});
