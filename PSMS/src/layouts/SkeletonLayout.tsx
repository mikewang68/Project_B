import { Button, Grid, Layout, Menu, Space, Tag, Typography, type MenuProps } from 'antd';
import { useEffect, useState } from 'react';
import { Link, Outlet, matchPath, useLocation } from 'react-router-dom';
import { authorizePage, loadCurrentDemoSession } from '../auth';
import { routeCatalog, type RouteGroup } from '../app/routeCatalog';

const groups: RouteGroup[] = ['调度', '执行', '安全', '治理'];

export default function SkeletonLayout() {
  const location = useLocation();
  const screens = Grid.useBreakpoint();
  const mobile = !screens.md;
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const session = loadCurrentDemoSession();
  const visibleRoutes = routeCatalog.filter((route) => authorizePage(session, route.id).allow);
  const navigationItems: MenuProps['items'] = groups.map((group) => ({
    key: group,
    type: 'group',
    label: group,
    children: visibleRoutes
      .filter((route) => route.group === group)
      .map((route) => ({
        key: route.id,
        label: (
          <Link to={route.smokePath} aria-label={`${route.id} ${route.name}`}>
            {route.id} {route.name}
          </Link>
        ),
      })),
  }));
  const selectedRoute = routeCatalog.find((route) =>
    matchPath({ path: route.path, end: true }, location.pathname),
  );

  useEffect(() => {
    setMobileNavigationOpen(false);
  }, [location.pathname]);

  return (
    <Layout className="skeleton-shell">
      <Layout.Sider
        width={244}
        collapsed={mobile && !mobileNavigationOpen}
        collapsedWidth={0}
        trigger={null}
        className={`skeleton-sider${mobileNavigationOpen ? ' is-mobile-open' : ''}`}
      >
        <div className="skeleton-brand">生产调度中心</div>
        <nav className="skeleton-navigation" aria-label="页面导航">
          <Menu
            className="skeleton-menu"
            theme="dark"
            mode="inline"
            items={navigationItems}
            selectedKeys={selectedRoute ? [selectedRoute.id] : []}
          />
        </nav>
      </Layout.Sider>
      {mobile && mobileNavigationOpen ? (
        <button
          type="button"
          className="skeleton-navigation-mask"
          aria-label="关闭页面导航"
          onClick={() => setMobileNavigationOpen(false)}
        />
      ) : null}
      <Layout className="skeleton-main">
        <Layout.Header className="skeleton-header">
          <Space size={10}>
            {mobile ? (
              <Button
                className="skeleton-menu-trigger"
                aria-label="打开页面导航"
                onClick={() => setMobileNavigationOpen(true)}
              >
                <span aria-hidden="true">☰</span>
              </Button>
            ) : null}
            <Typography.Title level={1} className="skeleton-header-title">
              B项目生产调度管理模块
            </Typography.Title>
          </Space>
          <Space size={12}>
            <Tag color="processing">静态演示</Tag>
            <Typography.Text className="skeleton-version">演示版 0.1</Typography.Text>
          </Space>
        </Layout.Header>
        <Layout.Content className="skeleton-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
