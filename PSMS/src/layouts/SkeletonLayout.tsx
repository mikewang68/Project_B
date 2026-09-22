import {
  Button,
  Drawer,
  Grid,
  Layout,
  Menu,
  Space,
  Tag,
  Tooltip,
  Typography,
  type MenuProps,
} from 'antd';
import { useEffect, useState } from 'react';
import { Link, Outlet, matchPath, useLocation } from 'react-router-dom';
import { authorizePage, loadCurrentDemoSession } from '../auth';
import { routeCatalog, type RouteGroup } from '../app/routeCatalog';
import {
  LAYOUT_OPTIONS,
  THEME_OPTIONS,
  usePreferenceStore,
} from '../stores/preference';
import { navIcon } from './navIcons';

const groups: RouteGroup[] = ['调度', '执行', '安全', '治理'];

const SIDEBAR_WIDTH = 220;
const SIDEBAR_COLLAPSED = 64;

function BrushIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M4 20.5c0-2 1.1-3.2 3-3.2s3 1.2 3 3.2H4z" />
      <path d="M9.6 16.6 20 6.2a1.7 1.7 0 0 0-2.4-2.4L7.2 14.2" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width="14"
      height="14"
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M4.5 12.5 9.5 17.5 19.5 6.5" />
    </svg>
  );
}

export default function SkeletonLayout() {
  const location = useLocation();
  const screens = Grid.useBreakpoint();
  const mobile = !screens.md;

  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [prefOpen, setPrefOpen] = useState(false);

  const layout = usePreferenceStore((state) => state.layout);
  const theme = usePreferenceStore((state) => state.theme);
  const setTheme = usePreferenceStore((state) => state.setTheme);
  const setLayout = usePreferenceStore((state) => state.setLayout);
  const reset = usePreferenceStore((state) => state.reset);

  const session = loadCurrentDemoSession();
  const visibleRoutes = routeCatalog.filter((route) => authorizePage(session, route.id).allow);

  const selectedRoute = routeCatalog.find((route) =>
    matchPath({ path: route.path, end: true }, location.pathname),
  );

  /** 侧栏菜单：按业务分组，用于 side / compact 布局 */
  const groupedItems: MenuProps['items'] = groups.map((group) => ({
    key: group,
    type: 'group',
    label: group,
    children: visibleRoutes
      .filter((route) => route.group === group)
      .map((route) => ({
        key: route.id,
        // 折叠态（64px 窄栏）下 antd 只渲染 icon，故每个页面都带独立图标
        icon: navIcon(route.id),
        title: `${route.id} ${route.name}`,
        label: (
          <Link to={route.smokePath} aria-label={`${route.id} ${route.name}`}>
            {route.id} {route.name}
          </Link>
        ),
      })),
  }));

  /** 顶部横向菜单：antd 的 horizontal 模式不支持分组，需扁平化 */
  const flatItems: MenuProps['items'] = visibleRoutes.map((route) => ({
    key: route.id,
    title: `${route.id} ${route.name}`,
    label: (
      <Link to={route.smokePath} aria-label={`${route.id} ${route.name}`}>
        {route.id} {route.name}
      </Link>
    ),
  }));

  useEffect(() => {
    setMobileNavigationOpen(false);
  }, [location.pathname]);

  const isTop = layout === 'top' && !mobile;
  const isCompact = layout === 'compact';
  const showSider = !isTop;

  const siderCollapsed = mobile ? !mobileNavigationOpen : isCompact || collapsed;
  const siderCollapsedWidth = mobile ? 0 : SIDEBAR_COLLAPSED;

  const selectedKeys = selectedRoute ? [selectedRoute.id] : [];

  return (
    <Layout className="skeleton-shell" data-layout={layout}>
      {showSider ? (
        <Layout.Sider
          width={SIDEBAR_WIDTH}
          collapsed={siderCollapsed}
          collapsedWidth={siderCollapsedWidth}
          trigger={null}
          className={`skeleton-sider${mobileNavigationOpen ? ' is-mobile-open' : ''}`}
        >
          <div className="skeleton-brand">生产调度中心</div>
          <nav className="skeleton-navigation" aria-label="页面导航">
            <Menu
              className="skeleton-menu"
              theme="dark"
              mode="inline"
              inlineCollapsed={siderCollapsed && !mobile}
              items={groupedItems}
              selectedKeys={selectedKeys}
            />
          </nav>
          {!mobile && !isCompact ? (
            <button
              type="button"
              className="skeleton-collapse"
              aria-label={collapsed ? '展开侧栏' : '折叠侧栏'}
              aria-expanded={!collapsed}
              onClick={() => setCollapsed((value) => !value)}
            >
              <span aria-hidden="true">{collapsed ? '»' : '«'}</span>
            </button>
          ) : null}
        </Layout.Sider>
      ) : null}

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
          <div className="skeleton-header-left">
            {mobile && showSider ? (
              <Button
                className="skeleton-menu-trigger"
                aria-label="打开页面导航"
                onClick={() => setMobileNavigationOpen(true)}
              >
                <span aria-hidden="true">☰</span>
              </Button>
            ) : null}
            {isTop ? (
              <>
                <span className="skeleton-brand-inline">生产调度中心</span>
                <Menu
                  className="skeleton-top-menu"
                  theme="light"
                  mode="horizontal"
                  items={flatItems}
                  selectedKeys={selectedKeys}
                />
              </>
            ) : (
              <Typography.Title level={1} className="skeleton-header-title">
                B项目生产调度管理模块
              </Typography.Title>
            )}
          </div>
          <Space size={12}>
            <Tooltip title="外观设置：切换皮肤与布局" placement="bottom">
              <Button
                className="skeleton-pref-trigger"
                aria-label="外观设置"
                onClick={() => setPrefOpen(true)}
              >
                <BrushIcon />
              </Button>
            </Tooltip>
            <Tag color="processing">静态演示</Tag>
            <Typography.Text className="skeleton-version">演示版 0.1</Typography.Text>
          </Space>
        </Layout.Header>
        <Layout.Content className="skeleton-content">
          <Outlet />
        </Layout.Content>
      </Layout>

      <Drawer
        title="外观设置"
        open={prefOpen}
        onClose={() => setPrefOpen(false)}
        width={330}
        className="pref-drawer"
      >
        <div className="pref-section">
          <div className="pref-section-title">皮肤主题（4 套）</div>
          <div className="theme-grid">
            {THEME_OPTIONS.map((option) => {
              const active = theme === option.id;
              return (
                <div
                  key={option.id}
                  className={`theme-card${active ? ' is-active' : ''}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={active}
                  onClick={() => setTheme(option.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setTheme(option.id);
                    }
                  }}
                >
                  <div className="theme-swatch" aria-hidden="true">
                    <span className="sw-sidebar" style={{ background: option.colors[0] }} />
                    <span className="sw-primary" style={{ background: option.colors[1] }} />
                  </div>
                  <div className="theme-meta">
                    <div className="theme-name">{option.name}</div>
                    <div className="theme-desc">{option.desc}</div>
                  </div>
                  {active ? (
                    <span className="theme-check">
                      <CheckIcon />
                    </span>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>

        <div className="pref-section">
          <div className="pref-section-title">布局方式（3 种）</div>
          <div className="layout-grid">
            {LAYOUT_OPTIONS.map((option) => {
              const active = layout === option.id;
              return (
                <div
                  key={option.id}
                  className={`layout-card${active ? ' is-active' : ''}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={active}
                  onClick={() => setLayout(option.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      setLayout(option.id);
                    }
                  }}
                >
                  <div className="layout-thumb" data-thumb={option.id} aria-hidden="true">
                    <span className="thumb-bar" />
                    <span className="thumb-body">
                      <i />
                      <i />
                    </span>
                  </div>
                  <div className="layout-name">{option.name}</div>
                  <div className="layout-desc">{option.desc}</div>
                </div>
              );
            })}
          </div>
        </div>

        <Button style={{ width: '100%', marginTop: 8 }} onClick={() => reset()}>
          恢复默认（科技蓝 · 左侧菜单）
        </Button>
      </Drawer>
    </Layout>
  );
}
