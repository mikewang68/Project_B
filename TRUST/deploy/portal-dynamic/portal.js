/**
 * Project_B 阶段 2-C.1：Portal 界面优化与真实导航能力核心脚本
 *
 * 核心模块划分：
 * 1. NavigationModel：严格按照 IAM v3 契约校验响应体与数据值域，阻断旧版 categories 及根级 accessMode；
 * 2. RouteAdapter：同源相对路径过滤与安全校验，防范 XSS、伪协议与目录穿越；
 * 3. ApiNavigationProvider：生产模式标准数据源，调用 GET /api/v1/iam/navigation/tree，拒绝任何 mock 参数回退；
 * 4. FixtureNavigationProvider：本地开发测试专用数据源，仅供隔离 dev-harness 加载；
 * 5. PermissionEvaluator：基于 data.operationPermissions[appCode] 与 readOnly 的精细化操作权限求值器；
 * 6. PortalApp：纯净正式界面交互、响应式抽屉与主题布局管理器。
 */
(() => {
  'use strict';

  // ==========================================
  // 1. 常量与配置
  // ==========================================
  const STORAGE_KEY = 'b-project-appearance';
  const V3_SCHEMA_VERSION = '3.0';
  const ALLOWED_APP_CODES = ['WMS', 'PSMS', 'SCS', 'EHMS', 'EMCS', 'TRUST', 'IAM', 'SYS'];

  const themes = [
    { id: 'tech-blue', name: '科技蓝', description: '深海军蓝导航 · 科技蓝主色（默认）', colors: ['#24364a', '#409eff'] },
    { id: 'forest-green', name: '护眼墨绿', description: '墨绿导航 · 统一科技蓝主色', colors: ['#20382f', '#409eff'] },
    { id: 'purple-elegant', name: '雅致深灰', description: '深灰导航 · 统一科技蓝主色', colors: ['#2c313a', '#409eff'] },
    { id: 'dark-pro', name: '暗夜黑', description: '深色海军蓝 · 暗光护眼', colors: ['#1f3043', '#409eff'] }
  ];

  const layouts = [
    { id: 'side', name: '左侧菜单', description: '经典纵向导航' },
    { id: 'top', name: '顶部导航', description: '横向菜单 · 内容全宽' },
    { id: 'compact', name: '图标窄栏', description: '窄侧栏 · 悬停提示' }
  ];

  // DOM 节点引用
  const frame = document.querySelector('#portal-frame');
  const sidebar = document.querySelector('#portal-sidebar');
  const sidebarToggle = document.querySelector('#sidebar-toggle');
  const sidebarBackdrop = document.querySelector('#sidebar-backdrop');
  const systemNav = document.querySelector('#system-nav');
  const sidebarLoading = document.querySelector('#sidebar-loading');
  const overlay = document.querySelector('#appearance-overlay');
  const panel = document.querySelector('#appearance-panel');

  // 用户与顶栏节点
  const userDisplayName = document.querySelector('#user-display-name');
  const userAccessMode = document.querySelector('#user-access-mode');
  const userReadonlyBadge = document.querySelector('#user-readonly-badge');
  const btnLogout = document.querySelector('#btn-logout');

  // 视图与卡片节点
  const crumbSystemName = document.querySelector('#crumb-system-name');
  const crumbPageName = document.querySelector('#crumb-page-name');
  const pageTitle = document.querySelector('#page-title');

  // 叶子卡片元素
  const pageLeafCard = document.querySelector('#page-leaf-card');
  const cardSystemCode = document.querySelector('#card-system-code');
  const cardPageName = document.querySelector('#card-page-name');
  const cardDestBadge = document.querySelector('#card-dest-badge');
  const cardReadonlyAlert = document.querySelector('#card-readonly-alert');
  const cardEntryPath = document.querySelector('#card-entry-path');
  const cardDestDesc = document.querySelector('#card-dest-desc');
  const btnNavigateEntry = document.querySelector('#btn-navigate-entry');

  // 空态与错误态节点
  const emptyStateCard = document.querySelector('#empty-state-card');
  const errorStateCard = document.querySelector('#error-state-card');
  const errorStateIcon = document.querySelector('#error-state-icon');
  const errorStateTitle = document.querySelector('#error-state-title');
  const errorStateDesc = document.querySelector('#error-state-desc');
  const errorStateCode = document.querySelector('#error-state-code');
  const btnErrorRetry = document.querySelector('#btn-error-retry');

  // 运行态状态
  let currentRequestId = 0;
  let activeNavigationData = null;
  let activeSystemNode = null;
  let activePageNode = null;
  let currentProvider = null;
  let lastFocus = null;

  // ==========================================
  // 2. 契约模型与校验器 (NavigationModel)
  // ==========================================
  const NavigationModel = {
    /**
     * 强校验服务端下发的导航响应体是否严格符合 v3 Schema
     */
    validateV3Contract(body) {
      if (!body || typeof body !== 'object') {
        throw new Error('Response body must be an object');
      }

      // 错误信封校验
      if (body.code !== 0) {
        const allowedErrors = [40001, 40101, 40301, 50001];
        if (!allowedErrors.includes(body.code)) {
          throw new Error(`Invalid error code: ${body.code}, must be one of ${allowedErrors.join(',')}`);
        }
        if (body.data !== null && body.data !== undefined) {
          throw new Error('Error response data must be null');
        }
        return { isError: true, code: body.code, message: body.message || 'Request failed' };
      }

      // 成功信封校验
      const data = body.data;
      if (!data || typeof data !== 'object') {
        throw new Error('Success response must contain a data object');
      }

      if (data.schemaVersion !== V3_SCHEMA_VERSION) {
        throw new Error(`Invalid schemaVersion: "${data.schemaVersion}". Must strictly be "${V3_SCHEMA_VERSION}"`);
      }

      // 根级拒绝旧版分类与根级 accessMode
      if ('categories' in data || 'category' in data) {
        throw new Error('Illegal legacy property "categories" present in v3 response');
      }
      if ('accessMode' in data) {
        throw new Error('accessMode cannot be at root data level; must be inside data.userContext');
      }

      // 用户上下文校验
      const uc = data.userContext;
      if (!uc || typeof uc !== 'object') {
        throw new Error('data.userContext is required');
      }
      if (typeof uc.displayName !== 'string' || typeof uc.readOnly !== 'boolean') {
        throw new Error('userContext requires valid displayName (string) and readOnly (boolean)');
      }
      const allowedModes = ['FULL_ADMIN', 'READONLY_AUDITOR', 'SCOPED_USER'];
      if (!allowedModes.includes(uc.accessMode)) {
        throw new Error(`Invalid accessMode: "${uc.accessMode}"`);
      }

      // 系统节点校验
      if (!Array.isArray(data.systems)) {
        throw new Error('data.systems must be an array');
      }

      for (const sys of data.systems) {
        if (!ALLOWED_APP_CODES.includes(sys.appCode)) {
          throw new Error(`Unauthorized or excluded appCode: "${sys.appCode}". DTS or lowercase codes are strictly rejected.`);
        }
        if (!sys.displayName || typeof sys.sortOrder !== 'number' || !sys.entryBasePath) {
          throw new Error(`SystemNode ${sys.appCode} missing required fields (displayName, sortOrder, entryBasePath)`);
        }
        if (!Array.isArray(sys.children)) {
          throw new Error(`SystemNode ${sys.appCode}.children must be an array`);
        }

        // E21：支持任意深度目录嵌套（数据库 normalized_level 上限为 6），兼容旧的两级结构
        const MAX_DEPTH = 6;
        const validateNode = (node, depth) => {
          if (depth > MAX_DEPTH) {
            throw new Error(`Illegal nesting deeper than ${MAX_DEPTH} levels at ${node && node.stableMenuKey}`);
          }
          if (!node.stableMenuKey || !node.hierarchyCode || !node.menuName) {
            throw new Error(`Node missing required fields: ${JSON.stringify(node)}`);
          }
          const isDir = (node.nodeType || 'PAGE').toUpperCase() === 'DIRECTORY';
          if (isDir) {
            if (!Array.isArray(node.children) || node.children.length === 0) {
              throw new Error(`DIRECTORY ${node.stableMenuKey} must have at least one visible child`);
            }
            // 目录不可跳转：出现入口路径即视为契约违规
            if (node.entryPath) {
              throw new Error(`DIRECTORY ${node.stableMenuKey} must not carry entryPath`);
            }
            for (const child of node.children) validateNode(child, depth + 1);
            return;
          }
          if (!['SPA_ROUTE', 'PAGE_KEY', 'TAB_TARGET'].includes(node.entryMode)) {
            throw new Error(`Invalid entryMode "${node.entryMode}" in ${node.stableMenuKey}`);
          }
          if (Array.isArray(node.children) && node.children.length > 0) {
            throw new Error(`PAGE ${node.stableMenuKey} must not have children`);
          }
          RouteAdapter.sanitizePath(node.entryPath);
        };
        for (const node of sys.children) validateNode(node, 2);
      }

      // 操作权限校验 (如果存在，必须为按 appCode 索引的字符串数组对象)
      if (data.operationPermissions && typeof data.operationPermissions === 'object') {
        for (const [appKey, perms] of Object.entries(data.operationPermissions)) {
          if (!Array.isArray(perms)) {
            throw new Error(`operationPermissions.${appKey} must be an array of permission strings`);
          }
        }
      }

      return { isError: false, data: data };
    }
  };

  // ==========================================
  // 3. 安全与路由适配器 (RouteAdapter)
  // ==========================================
  const RouteAdapter = {
    /**
     * 隔离测试环境判定：只有回环地址或显式注入的隔离标记才允许候选页直达。
     * 正式域名/正式门户目录永不满足，确保候选入口不可能出现在正式环境。
     */
    isIsolatedTestContext() {
      try {
        if (window.__ISOLATED_TEST_PORTAL__ === true) return true;
        const host = (window.location.hostname || '').toLowerCase();
        return host === '127.0.0.1' || host === 'localhost' || host === '::1';
      } catch (e) {
        return false;
      }
    },

    /**
     * 校验并净化路径，严禁任何外部 scheme、协议相对路径 //、反斜杠或路径遍历
     */
    sanitizePath(path) {
      if (!path || typeof path !== 'string') {
        throw new Error('Path must be a non-empty string');
      }
      const trimmed = path.trim();
      if (/[\\\u0000-\u0020\u007f]/.test(trimmed)) {
        throw new Error('Backslash and control/space characters are prohibited');
      }
      // 必须单斜杠开头
      if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.startsWith('\\')) {
        throw new Error('Path must start with a single "/" and cannot be protocol-relative');
      }
      const lower = trimmed.toLowerCase();
      if (lower.includes('javascript:') || lower.includes('data:') || lower.includes('vbscript:') || lower.includes('http:') || lower.includes('https:')) {
        throw new Error('Dangerous URI scheme detected');
      }
      // 路径遍历检查
      const decoded = decodeURIComponent(trimmed.split(/[?#]/, 1)[0]);
      const segments = decoded.split('/');
      if (segments.includes('..') || segments.includes('.') || /[\\\u0000-\u0020\u007f%]/.test(decoded) || decoded.startsWith('//')) {
        throw new Error('Path traversal sequence ".." is strictly prohibited');
      }
      return trimmed;
    },

    /** The base comes only from the authenticated IAM navigation response; never from query parameters. */
    resolveConfiguredAddress(system, entryPath) {
      if (!system.entryBaseUrl) return null;
      const raw = system.entryBaseUrl;
      if (typeof raw !== 'string' || /[\\\u0000-\u0020\u007f]/.test(raw)) throw new Error('Invalid configured address');
      const base = new URL(raw);
      if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password
          || base.search || base.hash || !base.pathname.endsWith('/')) throw new Error('Invalid configured address');
      this.sanitizePath(base.pathname);
      const prefix = this.sanitizePath(system.entryBasePath).replace(/\/$/, '');
      const path = this.sanitizePath(entryPath);
      const relative = new URL(path, 'https://navigation.invalid');
      if (relative.pathname !== prefix && !relative.pathname.startsWith(prefix + '/')) {
        throw new Error('Page does not belong to the configured application path');
      }
      // Never forward authentication material across application origins.
      for (const key of relative.searchParams.keys()) {
        if (/^(token|accesstoken|refreshtoken|idtoken|ticket|ssoticket|password|secret|authorization|session|sessionid|sid|code|authcode|csrf|csrftoken|apikey)$/i.test(key.replace(/[-_]/g, ''))) {
          throw new Error('Authentication query parameters cannot be forwarded');
        }
      }
      if (/token|ticket|password|secret|authorization|session|csrf/i.test(relative.hash)) {
        throw new Error('Authentication fragments cannot be forwarded');
      }
      base.pathname += relative.pathname.slice(prefix.length).replace(/^\//, '');
      base.search = relative.search;
      base.hash = relative.hash;
      return base.href;
    },

    /**
     * 解析目标跳转联调状态与安全显示信息
     */
    resolveDestination(system, pageLeaf) {
      const appCode = system.appCode;
      const sanitizedEntry = this.sanitizePath(pageLeaf.entryPath);
      const configuredAddress = this.resolveConfiguredAddress(system, sanitizedEntry);
      if (configuredAddress) {
        return {
          status: 'CONFIGURED_APPLICATION_ENTRY',
          badgeText: '已配置应用地址',
          badgeClass: 'dest-badge',
          entryPath: configuredAddress,
          desc: '应用访问地址已从导航配置生成；目标应用的登录与权限仍由其后端校验。'
        };
      }

      if (appCode === 'IAM' || appCode === 'SYS') {
        return {
          status: 'SPA_ROUTE_ACTIVE',
          badgeText: '业务路由已直达',
          badgeClass: 'dest-badge',
          entryPath: sanitizedEntry,
          desc: `目标服务入口路径 [${sanitizedEntry}] 已通过契约核验与同源校验，已接入受控联调网关。`
        };
      } else if (appCode === 'SCS' && this.isIsolatedTestContext()) {
        // E18：SCS 走同源 /safety/，页面与 /safety/api 均由隔离网关代理到真实 SCS
        return {
          status: 'SPA_ROUTE_ACTIVE',
          badgeText: '隔离测试入口',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `安全安防系统总览（隔离测试环境）。页面 [${sanitizedEntry}] 与 API 均经隔离网关接入真实 SCS 服务；该页尚未正式发布，正式门户不展示。`
        };
      } else if (appCode === 'TRUST' && this.isIsolatedTestContext()) {
        // E18：TRUST 使用其原生前端 + IAM 账号登录，统一门户仅做同源启动跳转
        return {
          status: 'SPA_ROUTE_ACTIVE',
          badgeText: '原生登录（已接 IAM）',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `可信存证系统（隔离测试环境）使用其原生前端与登录页，账号体系已接入 IAM（identityProvider=IAM）。统一网关以同源反向代理 [${sanitizedEntry}] 提供入口，保留其原生登录与 CSRF 校验。`
        };
      } else if (appCode === 'EMCS' && this.isIsolatedTestContext()) {
        // E18：EMCS 为 RuoYi 验证码登录，未接 SSO，统一门户只做启动跳转
        return {
          status: 'SPA_ROUTE_ACTIVE',
          badgeText: '原生登录（未接 SSO）',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `能源管控系统（隔离测试环境）使用 RuoYi 原生验证码登录，未接入统一 SSO。统一网关以同源反向代理 [${sanitizedEntry}] 提供入口，保留其验证码与登录流程。`
        };
      } else if ((appCode === 'WMS' || appCode === 'PSMS') && this.isIsolatedTestContext()) {
        // E20：WMS/PSMS 已核验的多条功能页在隔离环境直达；正式环境仍走原有分支
        return {
          status: 'SPA_ROUTE_ACTIVE',
          badgeText: '隔离测试入口',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `${system.displayName}功能页（隔离测试环境）。目标 [${sanitizedEntry}] 经该模块原生路由与可信 SSO 换票核验；该页尚未正式发布，正式门户不展示。`
        };
      } else if (appCode === 'EHMS') {
        return {
          status: 'PAGE_KEY_PENDING',
          badgeText: 'PAGE_KEY (适配待完成)',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `设备健康管理系统 (EHMS) 采用 ?navPageKey= 契约入口，模块尚未完成 1.7.0 网关参数监听适配，保持待联调状态。`
        };
      } else if (appCode === 'TRUST') {
        return {
          status: 'TAB_TARGET_PENDING',
          badgeText: 'TAB_TARGET (网关未联调)',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `可信存证系统 (TRUST) 采用 ?navTab= 契约入口，网关会话衔接尚待联调，保持待联调状态。`
        };
      } else {
        return {
          status: 'OWNER_PENDING',
          badgeText: '待模块审批 (OWNER_PENDING)',
          badgeClass: 'dest-badge pending',
          entryPath: sanitizedEntry,
          desc: `${system.displayName} 的功能页面正处于模块负责人核准与环境门禁审批流程中，未在目标环境放行。`
        };
      }
    }
  };

  // ==========================================
  // 4. 操作权限精细化求值器 (PermissionEvaluator)
  // ==========================================
  const PermissionEvaluator = {
    /**
     * 针对指定系统与操作权限码，校验是否允许执行
     * 规则：
     * 1. 对应 appCode 的 operationPermissions 中必须明确包含 requiredPermCode；
     * 2. 若属于写操作（isWriteAction === true）或敏感导出，则必须 !userContext.readOnly；
     * 3. FULL_ADMIN 在缺失具体权限码时亦不越权放行；
     * 4. 读操作通常亦需对应读权限。
     */
    checkPermission(activeData, appCode, requiredPermCode, isWriteAction = false) {
      if (!activeData || !activeData.userContext) {
        return { allowed: false, reason: '未获取到有效用户上下文' };
      }

      const uc = activeData.userContext;
      const appPerms = activeData.operationPermissions?.[appCode] || [];

      // 1. 检查具体权限码是否存在
      const hasCode = appPerms.includes(requiredPermCode);
      if (!hasCode) {
        return {
          allowed: false,
          code: requiredPermCode,
          reason: `缺失单项操作权限码 [${requiredPermCode}]（即使管理员模式亦不越权放行）`
        };
      }

      // 2. 检查只读状态硬拦截
      if (isWriteAction && uc.readOnly) {
        return {
          allowed: false,
          code: requiredPermCode,
          reason: `当前账号为只读受限模式，所有状态变更与敏感操作已被客户端与服务端内核硬否决`
        };
      }

      return {
        allowed: true,
        code: requiredPermCode,
        reason: '操作权限核验通过'
      };
    }
  };

  // ==========================================
  // 5. 导航数据提供者 (Provider)
  // ==========================================

  /**
   * 生产正式环境 Provider：仅通过受控 API 获取，绝不使用本地 mock 或静态文件回退
   */
  class ApiNavigationProvider {
    constructor(apiEndpoint = '/api/v1/iam/navigation/tree') {
      this.apiEndpoint = apiEndpoint;
      this.authToken = null;
    }

    setAuthToken(token) {
      this.authToken = token;
    }

    async fetchNavigation() {
      const headers = {
        'Accept': 'application/json',
        'Cache-Control': 'no-cache'
      };
      if (this.authToken) {
        headers['Authorization'] = `Bearer ${this.authToken}`;
      }
      const response = await fetch(this.apiEndpoint, {
        method: 'GET',
        headers: headers,
        cache: 'no-store'
      });

      let jsonBody = null;
      try {
        jsonBody = await response.json();
      } catch (e) {
        // 非 JSON 响应，直接按 HTTP 状态码归类
        if (response.status === 401) {
          return { code: 40101, message: '未登录或登录已过期', data: null };
        } else if (response.status === 403) {
          return { code: 40301, message: '无权访问导航服务', data: null };
        } else {
          return { code: 50001, message: `服务端响应解析异常 (HTTP ${response.status})`, data: null };
        }
      }

      return jsonBody;
    }
  }

  /**
   * 本地测试专用 Provider：仅在本地回环测试与专用 dev-harness 中加载离线 fixture
   */
  class FixtureNavigationProvider {
    constructor(fixturePath = '/fixtures/batch1_superadmin.json') {
      this.fixturePath = fixturePath;
    }

    setFixture(path) {
      this.fixturePath = path;
    }

    async fetchNavigation() {
      const response = await fetch(this.fixturePath, { cache: 'no-store' });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} failed to load fixture: ${this.fixturePath}`);
      }
      return await response.json();
    }
  }

  // ==========================================
  // 6. 外观设置管理器 (AppearanceManager)
  // ==========================================
  const AppearanceManager = {
    readPreference() {
      try {
        const cookie = document.cookie.split('; ').find(item => item.startsWith(STORAGE_KEY + '='));
        const raw = cookie ? decodeURIComponent(cookie.slice(STORAGE_KEY.length + 1)) : localStorage.getItem(STORAGE_KEY);
        const value = JSON.parse(raw || '{}');
        return {
          theme: themes.some(item => item.id === value.theme) ? value.theme : 'tech-blue',
          layout: layouts.some(item => item.id === value.layout) ? value.layout : 'side'
        };
      } catch {
        return { theme: 'tech-blue', layout: 'side' };
      }
    },

    persistPreference(pref) {
      const value = JSON.stringify(pref);
      try { localStorage.setItem(STORAGE_KEY, value); } catch {}
      document.cookie = STORAGE_KEY + '=' + encodeURIComponent(value) + '; Path=/; Max-Age=31536000; SameSite=Lax';
    },

    applyAppearance(pref) {
      document.documentElement.dataset.theme = pref.theme;
      document.documentElement.style.colorScheme = pref.theme === 'dark-pro' ? 'dark' : 'light';
      frame.dataset.layout = pref.layout;
      const meta = document.querySelector('meta[name="theme-color"]');
      if (meta) {
        meta.content = pref.theme === 'dark-pro' ? '#1f3043' :
          ({ 'forest-green': '#20382f', 'purple-elegant': '#2c313a' }[pref.theme] || '#24364a');
      }

      document.querySelectorAll('[data-theme-option]').forEach(button => {
        const active = button.dataset.themeOption === pref.theme;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
        const check = button.querySelector('.option-check');
        if (check) check.hidden = !active;
      });

      document.querySelectorAll('[data-layout-option]').forEach(button => {
        const active = button.dataset.layoutOption === pref.layout;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
      });
    },

    buildOptions(onUpdate) {
      const themeRoot = document.querySelector('#theme-options');
      if (themeRoot && themeRoot.children.length === 0) {
        for (const theme of themes) {
          const button = document.createElement('button');
          button.className = 'theme-option';
          button.type = 'button';
          button.dataset.themeOption = theme.id;

          const swatch = document.createElement('span');
          swatch.className = 'theme-swatch';
          swatch.setAttribute('aria-hidden', 'true');
          const navColor = document.createElement('i');
          const primaryColor = document.createElement('i');
          navColor.style.background = theme.colors[0];
          primaryColor.style.background = theme.colors[1];
          swatch.append(navColor, primaryColor);

          const copy = document.createElement('span');
          copy.className = 'option-copy';
          const strong = document.createElement('strong');
          strong.textContent = theme.name;
          const small = document.createElement('small');
          small.textContent = theme.description;
          copy.append(strong, small);

          const check = document.createElement('span');
          check.className = 'option-check';
          check.textContent = '✓';
          check.setAttribute('aria-hidden', 'true');

          button.append(swatch, copy, check);
          button.addEventListener('click', () => onUpdate('theme', theme.id));
          themeRoot.append(button);
        }
      }

      const layoutRoot = document.querySelector('#layout-options');
      if (layoutRoot && layoutRoot.children.length === 0) {
        for (const layout of layouts) {
          const button = document.createElement('button');
          button.className = 'layout-option';
          button.type = 'button';
          button.dataset.layoutOption = layout.id;

          const thumb = document.createElement('span');
          thumb.className = 'layout-thumb';
          thumb.dataset.thumb = layout.id;
          thumb.setAttribute('aria-hidden', 'true');
          const bar = document.createElement('i');
          bar.className = 'thumb-bar';
          const body = document.createElement('i');
          body.className = 'thumb-body';
          body.append(document.createElement('b'), document.createElement('b'));
          thumb.append(bar, body);

          const strong = document.createElement('strong');
          strong.textContent = layout.name;
          const small = document.createElement('small');
          small.textContent = layout.description;

          button.append(thumb, strong, small);
          button.addEventListener('click', () => onUpdate('layout', layout.id));
          layoutRoot.append(button);
        }
      }
    }
  };

  // ==========================================
  // 7. 门户 UI 渲染核心 (PortalApp)
  // ==========================================
  const PortalApp = {
    preference: null,

    init(options = {}) {
      // 1. 设置数据提供者 (默认正式 ApiNavigationProvider，绝不在普通模式读取 fixture)
      currentProvider = options.provider || new ApiNavigationProvider();

      // 2. 主题与布局初始化
      this.preference = AppearanceManager.readPreference();
      AppearanceManager.buildOptions((type, val) => {
        this.preference[type] = val;
        AppearanceManager.persistPreference(this.preference);
        AppearanceManager.applyAppearance(this.preference);
      });
      AppearanceManager.applyAppearance(this.preference);

      // 3. 事件绑定
      this.bindEvents();

      // 安全合规整改 (P0 / RISK-01): 严禁通过 URL Query 或 Hash 消费/传递认证凭据
      // 若 URL 中携带 token/access_token 等敏感参数，立刻从地址栏剥离并记录告警，严格拒绝自动建立会话
      if (window.location.search.includes('token') || window.location.hash.includes('token') ||
          window.location.search.includes('access_token') || window.location.hash.includes('access_token')) {
        const cleanSearch = window.location.search
          .replace(/[?&](token|access_token)=[^&#]*/gi, '')
          .replace(/^&/, '?');
        const cleanHash = window.location.hash
          .replace(/[#&](token|access_token)=[^&#]*/gi, '')
          .replace(/^&/, '#');
        const cleanUrl = window.location.pathname + cleanSearch + cleanHash;
        window.history.replaceState({}, document.title, cleanUrl || '/');
        console.warn('Security Alert: Authentication credential in URL is strictly prohibited and was purged.');
      }

      const urlParams = new URLSearchParams(window.location.search);
      if (urlParams.get('drawer_open') === '1' && sidebar) {
        sidebar.classList.add('open');
        if (sidebarBackdrop) sidebarBackdrop.classList.add('active');
      }

      // 4. 自动检测同源会话并加载导航
      this.initSessionAndNavigation();
    },

    bindEvents() {
      // 移动端侧边栏切换
      if (sidebarToggle) {
        sidebarToggle.addEventListener('click', () => {
          const isOpen = sidebar.classList.toggle('open');
          sidebarToggle.setAttribute('aria-expanded', String(isOpen));
          if (sidebarBackdrop) {
            sidebarBackdrop.classList.toggle('active', isOpen);
          }
          if (isOpen) {
            document.body.classList.add('drawer-open');
          } else {
            document.body.classList.remove('drawer-open');
          }
        });
      }

      // 遮罩层点击关闭抽屉
      if (sidebarBackdrop) {
        sidebarBackdrop.addEventListener('click', () => this.closeMobileDrawer());
      }

      // 全局键盘 ESC 监听
      window.addEventListener('keydown', e => {
        if (e.key === 'Escape') {
          if (sidebar && sidebar.classList.contains('open')) {
            this.closeMobileDrawer();
          }
          if (overlay && !overlay.hidden) {
            this.closeAppearance();
          }
        }
      });

      // 外观弹窗事件
      document.querySelectorAll('[data-appearance-open]').forEach(btn => {
        btn.addEventListener('click', e => {
          lastFocus = e.currentTarget;
          overlay.hidden = false;
          document.body.classList.add('appearance-open');
          panel.focus();
        });
      });

      const appearanceClose = document.querySelector('.appearance-close');
      if (appearanceClose) {
        appearanceClose.addEventListener('click', () => this.closeAppearance());
      }

      const appearanceReset = document.querySelector('.appearance-reset');
      if (appearanceReset) {
        appearanceReset.addEventListener('click', () => {
          this.preference = { theme: 'tech-blue', layout: 'side' };
          AppearanceManager.persistPreference(this.preference);
          AppearanceManager.applyAppearance(this.preference);
        });
      }

      if (overlay) {
        overlay.addEventListener('mousedown', e => {
          if (e.target === overlay) this.closeAppearance();
        });
      }

      // 登出按钮
      if (btnLogout) {
        btnLogout.addEventListener('click', () => this.handleLogout());
      }

      // 错误重试按钮
      if (btnErrorRetry) {
        btnErrorRetry.addEventListener('click', () => this.loadNavigation());
      }

      // 受控登录表单提交 (短期凭据仅在内存流转，绝不写持久化存储)
      const loginForm = document.querySelector('#portal-login-form');
      if (loginForm) {
        loginForm.addEventListener('submit', async (e) => {
          e.preventDefault();
          const usernameInput = document.querySelector('#login-username');
          const passwordInput = document.querySelector('#login-password');
          const username = usernameInput ? usernameInput.value.trim() : '';
          const password = passwordInput ? passwordInput.value : '';
          const msg = document.querySelector('#login-msg');

          if (!username || !password) {
            if (msg) {
              msg.textContent = '请输入登录账号与口令';
              msg.hidden = false;
            }
            return;
          }

          try {
            const resp = await fetch('/api/v1/iam/auth/login', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
              body: JSON.stringify({ username, password })
            });
            const res = await resp.json();
            if (resp.ok && res.code === 0 && res.data && res.data.token) {
              const token = res.data.token;
              if (currentProvider && currentProvider.setAuthToken) {
                currentProvider.setAuthToken(token);
              }
              loginForm.hidden = true;
              if (msg) msg.hidden = true;
              if (passwordInput) passwordInput.value = '';
              this.loadNavigation();
            } else {
              if (msg) {
                msg.textContent = res.message || '登录失败，请核对账号与密码';
                msg.hidden = false;
              }
            }
          } catch (err) {
            if (msg) {
              msg.textContent = `登录网络请求异常: ${err.message}`;
              msg.hidden = false;
            }
          }
        });
      }

      // 目标页面导航按钮点击拦截 (防止脱离控制)
      if (btnNavigateEntry) {
        btnNavigateEntry.addEventListener('click', e => {
          if (!activePageNode) return;
          const targetUrl = btnNavigateEntry.getAttribute('href');
          if (targetUrl === '#' || !targetUrl.startsWith('/')) {
            e.preventDefault();
            alert('目标服务实例未部署或网关尚未接通，无法跳转。');
          }
        });
      }

      // 监听视口尺寸变化，视口大于 768px 时自动收起移动端抽屉
      window.addEventListener('resize', () => {
        if (window.innerWidth > 768) {
          this.closeMobileDrawer();
        }
      });
    },

    closeMobileDrawer() {
      if (sidebar) sidebar.classList.remove('open');
      if (sidebarToggle) sidebarToggle.setAttribute('aria-expanded', 'false');
      if (sidebarBackdrop) sidebarBackdrop.classList.remove('active');
      document.body.classList.remove('drawer-open');
    },

    closeAppearance() {
      if (overlay) overlay.hidden = true;
      document.body.classList.remove('appearance-open');
      lastFocus?.focus();
    },

    /**
     * 自动检测同源服务端会话并水合
     */
    async initSessionAndNavigation() {
      try {
        const resp = await fetch('/api/v1/portal/session/me', {
          headers: { 'Accept': 'application/json' }
        });
        if (resp.ok) {
          const res = await resp.json();
          if (res.code === 0 && res.data && res.data.authenticated) {
            if (currentProvider && currentProvider.setAuthToken && res.data.token) {
              currentProvider.setAuthToken(res.data.token);
            }
          }
        }
      } catch (e) {
        // 忽略网络探针异常
      }
      this.loadNavigation();
    },

    /**
     * 加载导航数据 (发号调度防竞态)
     */
    async loadNavigation() {
      const requestId = ++currentRequestId;
      sidebarLoading.hidden = false;
      sidebarLoading.textContent = '正在读取授权导航...';
      systemNav.replaceChildren(sidebarLoading);

      try {
        const rawJson = await currentProvider.fetchNavigation();

        // 并发竞态检查
        if (requestId !== currentRequestId) {
          console.warn('Discarding outdated navigation response', requestId);
          return;
        }

        // 强契约校验
        const validation = NavigationModel.validateV3Contract(rawJson);

        if (validation.isError) {
          this.renderError(validation.code, validation.message);
          return;
        }

        // 校验通过，渲染导航
        activeNavigationData = validation.data;
        this.renderNavigation(activeNavigationData);
      } catch (err) {
        if (requestId !== currentRequestId) return;
        console.error('Navigation fetch or validation failed:', err);
        this.renderError(50001, `导航服务访问异常: ${err.message}`);
      }
    },

    /**
     * 渲染完整导航与顶部用户上下文
     */
    renderNavigation(data) {
      const uc = data.userContext;

      // 隐藏登录与错误状态
      const loginForm = document.querySelector('#portal-login-form');
      if (loginForm) loginForm.hidden = true;
      if (errorStateCard) errorStateCard.hidden = true;

      // 1. 更新顶部用户信息
      userDisplayName.textContent = uc.displayName;
      userAccessMode.textContent = uc.accessMode;
      userReadonlyBadge.hidden = !uc.readOnly;
      btnLogout.hidden = false;

      // 2. 清空并准备侧边栏容器
      systemNav.replaceChildren();

      const systems = data.systems;

      // 3. 空系统树状态检查
      if (!systems || systems.length === 0) {
        this.renderEmptyState();
        return;
      }

      // 4. 排序系统 (按 sortOrder 升序)
      const sortedSystems = [...systems].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));

      let firstPageToSelect = null;
      let firstSystemOfPage = null;

      // 5. 渲染两级导航树
      for (let sysIndex = 0; sysIndex < sortedSystems.length; sysIndex++) {
        const sys = sortedSystems[sysIndex];
        if (!sys.children || sys.children.length === 0) {
          continue; // 一级系统无二级功能时剪枝
        }

        const group = document.createElement('div');
        group.className = 'system-group';

        // 一级系统标题按钮
        const sysBtn = document.createElement('button');
        sysBtn.type = 'button';
        sysBtn.className = 'system-header-btn';
        sysBtn.setAttribute('aria-expanded', sysIndex === 0 ? 'true' : 'false');
        sysBtn.setAttribute('aria-controls', `page-list-${sys.appCode}`);
        sysBtn.title = sys.displayName;

        const badge = document.createElement('span');
        badge.className = 'sys-code-badge';
        badge.textContent = sys.appCode;

        const nameSpan = document.createElement('span');
        nameSpan.className = 'sys-name';
        nameSpan.textContent = sys.displayName;

        const chevron = document.createElement('span');
        chevron.className = 'chevron';
        chevron.textContent = '▶';
        chevron.setAttribute('aria-hidden', 'true');

        sysBtn.append(badge, nameSpan, chevron);

        // 二级页面列表容器
        const pageList = document.createElement('div');
        pageList.className = 'page-list';
        pageList.id = `page-list-${sys.appCode}`;
        pageList.hidden = sysIndex !== 0;

        // 展开/收起点击事件
        sysBtn.addEventListener('click', () => {
          const expanded = sysBtn.getAttribute('aria-expanded') === 'true';
          sysBtn.setAttribute('aria-expanded', String(!expanded));
          pageList.hidden = expanded;
        });

        // E21：递归渲染 DIRECTORY / PAGE（目录可展开，页面可点击直达）
        const renderNodes = (nodes, container, depth) => {
          const ordered = [...nodes].sort((a, b) => {
            const ao = typeof a.sortOrder === 'number' ? a.sortOrder : 0;
            const bo = typeof b.sortOrder === 'number' ? b.sortOrder : 0;
            return ao - bo;
          });
          for (const node of ordered) {
            const isDir = (node.nodeType || 'PAGE').toUpperCase() === 'DIRECTORY'
              || (Array.isArray(node.children) && node.children.length > 0);

            if (isDir) {
              const dirWrap = document.createElement('div');
              dirWrap.className = 'nav-directory';
              dirWrap.dataset.dirKey = node.stableMenuKey;

              const dirBtn = document.createElement('button');
              dirBtn.type = 'button';
              dirBtn.className = 'dir-header-btn';
              dirBtn.style.paddingLeft = (10 + depth * 12) + 'px';
              dirBtn.setAttribute('aria-expanded', 'true');
              const dirChevron = document.createElement('span');
              dirChevron.className = 'dir-chevron';
              dirChevron.textContent = '▾';
              const dirName = document.createElement('span');
              dirName.className = 'dir-name';
              dirName.textContent = node.menuName;
              dirBtn.append(dirChevron, dirName);

              const childBox = document.createElement('div');
              childBox.className = 'dir-children';

              dirBtn.addEventListener('click', () => {
                const expanded = dirBtn.getAttribute('aria-expanded') === 'true';
                dirBtn.setAttribute('aria-expanded', String(!expanded));
                childBox.hidden = expanded;
                dirChevron.textContent = expanded ? '▸' : '▾';
              });

              renderNodes(node.children || [], childBox, depth + 1);
              dirWrap.append(dirBtn, childBox);
              container.append(dirWrap);
              if (!firstPageToSelect) {
                const firstLeaf = this.findFirstPage(node.children || []);
                if (firstLeaf) { firstPageToSelect = firstLeaf; firstSystemOfPage = sys; }
              }
              continue;
            }

            // PAGE 叶子
            const pageBtn = document.createElement('button');
            pageBtn.type = 'button';
            pageBtn.className = 'page-btn';
            pageBtn.dataset.menuKey = node.stableMenuKey;
            pageBtn.title = node.menuName;
            if (depth > 0) pageBtn.style.paddingLeft = (18 + depth * 12) + 'px';

            const pName = document.createElement('span');
            pName.className = 'page-name';
            pName.textContent = node.menuName;
            pageBtn.append(pName);

            pageBtn.addEventListener('click', () => {
              const destInfo = RouteAdapter.resolveDestination(sys, node);
              if (destInfo.entryPath && destInfo.status === 'SPA_ROUTE_ACTIVE') {
                window.location.href = destInfo.entryPath;
                return;
              }
              this.selectPage(sys, node);
              if (window.innerWidth <= 768) {
                this.closeMobileDrawer();
              }
            });

            container.append(pageBtn);
            if (!firstPageToSelect) {
              firstPageToSelect = node;
              firstSystemOfPage = sys;
            }
          }
        };

        renderNodes(sys.children, pageList, 0);

        group.append(sysBtn, pageList);
        systemNav.append(group);
      }

      // 6. 默认选中首个页面
      if (firstPageToSelect) {
        this.selectPage(firstSystemOfPage, firstPageToSelect);
      } else {
        this.renderEmptyState();
      }
    },

    /**
     * E21：在（可能嵌套的）节点集合中找第一个可点击 PAGE
     */
    findFirstPage(nodes) {
      for (const n of nodes || []) {
        const isDir = (n.nodeType || 'PAGE').toUpperCase() === 'DIRECTORY'
          || (Array.isArray(n.children) && n.children.length > 0);
        if (!isDir) return n;
        const inner = this.findFirstPage(n.children || []);
        if (inner) return inner;
      }
      return null;
    },

    /**
     * 选中并激活指定页面节点
     */
    selectPage(system, pageLeaf) {
      activeSystemNode = system;
      activePageNode = pageLeaf;

      // 1. 更新侧边栏高亮
      document.querySelectorAll('.page-btn').forEach(btn => {
        const isActive = btn.dataset.menuKey === pageLeaf.stableMenuKey;
        btn.classList.toggle('active', isActive);
        if (isActive) {
          btn.setAttribute('aria-current', 'page');
        } else {
          btn.removeAttribute('aria-current');
        }
      });

      // 2. 更新面包屑与标题
      crumbSystemName.textContent = system.displayName;
      crumbPageName.textContent = pageLeaf.menuName;
      pageTitle.textContent = pageLeaf.menuName;

      // 3. 解析入口跳转状态
      const destInfo = RouteAdapter.resolveDestination(system, pageLeaf);

      // 4. 更新功能卡片展示
      cardSystemCode.textContent = system.appCode;
      cardPageName.textContent = pageLeaf.menuName;
      cardDestBadge.textContent = destInfo.badgeText;
      cardDestBadge.className = destInfo.badgeClass;
      cardEntryPath.textContent = destInfo.entryPath;
      cardDestDesc.textContent = destInfo.desc;
      btnNavigateEntry.setAttribute('href', destInfo.entryPath);

      // 5. 只读状态展示
      const isReadOnly = Boolean(activeNavigationData?.userContext?.readOnly);
      cardReadonlyAlert.hidden = !isReadOnly;

      // 6. 显示卡片，隐藏空态与错误
      pageLeafCard.hidden = false;
      emptyStateCard.hidden = true;
      errorStateCard.hidden = true;
    },

    /**
     * 空系统树渲染
     */
    renderEmptyState() {
      pageLeafCard.hidden = true;
      errorStateCard.hidden = true;
      emptyStateCard.hidden = false;

      crumbSystemName.textContent = '暂无已放行系统';
      crumbPageName.textContent = '空状态';
      pageTitle.textContent = '当前暂无可访问功能';
    },

    /**
     * 错误状态渲染 (400, 401, 403, 500)
     */
    renderError(code, message) {
      activeNavigationData = null;
      activeSystemNode = null;
      activePageNode = null;

      // 清空侧边栏
      systemNav.replaceChildren();

      // 清空顶部用户上下文
      userDisplayName.textContent = '未认证/受限';
      userAccessMode.textContent = 'NONE';
      userReadonlyBadge.hidden = true;
      btnLogout.hidden = true;

      // 隐藏其他视图
      pageLeafCard.hidden = true;
      emptyStateCard.hidden = true;
      errorStateCard.hidden = false;

      // 更新错误文案
      errorStateCode.textContent = `ERROR_CODE: ${code}`;

      const loginForm = document.querySelector('#portal-login-form');
      const errActions = document.querySelector('#error-actions');

      if (code === 40101) {
        errorStateIcon.textContent = '🔒';
        errorStateTitle.textContent = '未登录或登录已过期 (40101)';
        errorStateDesc.textContent = message || '请通过受控身份认证入口输入账号与口令（短期会话仅存内存）。';
        if (loginForm) loginForm.hidden = false;
        if (errActions) errActions.hidden = true;
      } else if (code === 40301) {
        if (loginForm) loginForm.hidden = true;
        if (errActions) errActions.hidden = false;
        errorStateIcon.textContent = '🚫';
        errorStateTitle.textContent = '访问受限或账户已被禁用 (40301)';
        errorStateDesc.textContent = message || '当前登录主体无权访问导航服务或账号状态受限。';
      } else if (code === 40001) {
        if (loginForm) loginForm.hidden = true;
        if (errActions) errActions.hidden = false;
        errorStateIcon.textContent = '⚠️';
        errorStateTitle.textContent = '请求参数错误 (40001)';
        errorStateDesc.textContent = message || '请求参数非法，拒绝提供导航视图。';
      } else {
        if (loginForm) loginForm.hidden = true;
        if (errActions) errActions.hidden = false;
        errorStateIcon.textContent = '💥';
        errorStateTitle.textContent = '导航服务不可用 (50001)';
        errorStateDesc.textContent = message || '导航服务不可用或网络异常，请稍后刷新重试。严格拒绝降级为旧静态卡片。';
      }

      crumbSystemName.textContent = '安全拦截';
      crumbPageName.textContent = '异常提示';
      pageTitle.textContent = errorStateTitle.textContent;
    },

    /**
     * 登出会话处理
     */
    async handleLogout() {
      try {
        await fetch('/api/v1/portal/session/logout', {
          method: 'POST',
          headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json'
          }
        });
      } catch (e) {
        console.warn('Logout API error:', e);
      } finally {
        if (currentProvider && currentProvider.setAuthToken) {
          currentProvider.setAuthToken(null);
        }
        this.renderError(40101, '会话已安全退出，请重新登录');
      }
    }
  };

  // 暴露给自动化测试脚本与隔离 dev-harness 的公共接口
  window.__PORTAL_APP__ = PortalApp;
  window.__API_PROVIDER__ = ApiNavigationProvider;
  window.__FIXTURE_PROVIDER__ = FixtureNavigationProvider;
  window.__NAVIGATION_CLIENT__ = NavigationModel;
  window.__ROUTE_ADAPTER__ = RouteAdapter;
  window.__PERMISSION_EVALUATOR__ = PermissionEvaluator;

  // DOM 就绪后启动
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => PortalApp.init());
  } else {
    PortalApp.init();
  }
})();
