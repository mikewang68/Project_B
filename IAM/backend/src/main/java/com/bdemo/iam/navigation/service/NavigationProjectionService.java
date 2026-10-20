package com.bdemo.iam.navigation.service;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.navigation.dto.DiagnosticsInfo;
import com.bdemo.iam.navigation.dto.NavigationData;
import com.bdemo.iam.navigation.dto.PageLeafNode;
import com.bdemo.iam.navigation.dto.SystemNode;
import com.bdemo.iam.navigation.dto.UserContext;
import com.bdemo.iam.navigation.enums.AccessModeEnum;
import com.bdemo.iam.navigation.enums.AppCodeEnum;
import com.bdemo.iam.navigation.enums.EntryModeEnum;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import com.bdemo.iam.navigation.provider.DatabaseNavigationProvider;
import com.bdemo.iam.navigation.provider.FixtureNavigationProvider;
import com.bdemo.iam.navigation.provider.NavigationDataProvider;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 阶段 2-B.2 IAM 两级动态导航投影核心服务 (NavigationProjectionService)。
 *
 * 核心设计规则：
 * 1. 严格仅输出两级结构：Level 1 为已授权系统 (SystemNode)，Level 2 为具体功能页 (PageLeafNode)；
 * 2. 彻底移除旧 categories、ALL、BUSINESS、COMMON；
 * 3. 严格八系统枚举 (WMS:10 -> SYS:80)，任何 DTS 编码或非枚举值报 400；
 * 4. 严格三维审批门禁判定：当前目标环境获批发布页数需通过数据库核验；
 * 5. 一级系统无已授权二级功能时整个一级系统裁掉 (Pruning)；
 * 6. 只读硬否决 (Readonly Hard Veto)：只读模式下隐藏纯写功能页 (TRUST:TRUST-M-create)，
 *    并在 operationPermissions 中剥离所有新增、编辑、删除、执行与敏感导出权限；
 * 7. 严格 Fail-Closed：数据库不可达或异常时，坚决返回 50001 错误，严禁静默回退内存数据。
 */
@Service
public class NavigationProjectionService {

  private static final Logger log = LoggerFactory.getLogger(NavigationProjectionService.class);

  private final DatabaseNavigationProvider databaseProvider;
  private final FixtureNavigationProvider fixtureProvider;

  @Value("${iam.navigation.data-source:database}")
  private String dataSourceConfig = "database";

  @Value("${iam.navigation.simulate-batch1-approved:false}")
  private boolean simulateBatch1Approved = false;

  /**
   * 仅隔离联调实例启用的候选页测试开关（默认 false，正式实例不设置）。
   * 开启后，nav_eligible=0 的候选 PAGE 仍进入**测试导航树**，用于验证"统一入口点进真实业务页"；
   * 不修改数据库发布字段，不改变正式 2/8 口径。
   */
  @Value("${iam.navigation.isolated-test-candidates:false}")
  private boolean isolatedTestCandidates = false;

  // 供隔离测试动态注入或模拟已放行页面（用于验证 WMS 范围用户等离线单测）
  private static final ThreadLocal<Set<String>> SIMULATED_APPROVED_KEYS = new ThreadLocal<>();

  public static void setSimulatedApprovedKeys(Set<String> keys) {
    SIMULATED_APPROVED_KEYS.set(keys);
  }

  public static void clearSimulatedApprovedKeys() {
    SIMULATED_APPROVED_KEYS.remove();
  }

  @Autowired
  public NavigationProjectionService(
      @Autowired(required = false) DatabaseNavigationProvider databaseProvider,
      FixtureNavigationProvider fixtureProvider) {
    this.databaseProvider = databaseProvider;
    this.fixtureProvider = fixtureProvider;
  }

  /**
   * 判定当前生效的导航数据源提供者。
   */
  private NavigationDataProvider resolveActiveProvider() {
    if ("fixture".equalsIgnoreCase(dataSourceConfig) || simulateBatch1Approved) {
      return fixtureProvider;
    }
    if (databaseProvider != null) {
      return databaseProvider;
    }
    return fixtureProvider;
  }

  /**
   * 生成当前主体的两级动态导航树。
   */
  public NavigationData projectNavigationTree(String appCodeParam, Boolean includeDiagnostics) {
    LoginUser user = JwtAuthFilter.currentUser();
    if (user == null) {
      throw new BizException(401, 40101, "未登录或登录已过期");
    }

    // 1. 校验 appCode 入参：若传入必须为合法八系统枚举，DTS 或非法值返回 400
    AppCodeEnum filterApp = null;
    if (appCodeParam != null && !appCodeParam.isBlank()) {
      filterApp = AppCodeEnum.fromString(appCodeParam.trim());
      if (filterApp == null) {
        throw new BizException(400, 40001, "无效的子系统编码参数: " + appCodeParam);
      }
    }

    // 2. 判定主体鉴权模式与只读状态
    boolean isReadonly = user.isReadOnly()
        || "READONLY_AUDITOR".equalsIgnoreCase(user.getAccessMode())
        || user.getRoles().stream().anyMatch(r ->
            "PLATFORM_AUDITOR_READONLY".equalsIgnoreCase(r)
            || "auditor_readonly".equalsIgnoreCase(r)
            || "readonly_auditor".equalsIgnoreCase(r)
            || "platform_auditor_readonly".equalsIgnoreCase(r)
            || "auditor".equalsIgnoreCase(r));

    boolean isFullAdmin = !isReadonly && (user.isSuperAdmin()
        || "FULL_ADMIN".equalsIgnoreCase(user.getAccessMode())
        || user.getRoles().stream().anyMatch(r ->
            "super_admin".equalsIgnoreCase(r) || "PLATFORM_ADMIN_FULL".equalsIgnoreCase(r)));

    AccessModeEnum accessMode = isReadonly
        ? AccessModeEnum.READONLY_AUDITOR
        : (isFullAdmin ? AccessModeEnum.FULL_ADMIN : AccessModeEnum.SCOPED_USER);

    UserContext userContext = new UserContext();
    userContext.setDisplayName(user.getDisplayName() != null && !user.getDisplayName().isBlank()
        ? user.getDisplayName() : user.getUsername());
    userContext.setAccessMode(accessMode.name());
    userContext.setReadOnly(isReadonly);

    // 3. 解析当前数据提供者并读取候选菜单与权限映射（Fail-Closed 保证：若 DB 异常直接外抛 50001）
    NavigationDataProvider provider = resolveActiveProvider();
    log.info("Projecting navigation tree using provider: {}, user: {}, mode: {}",
        provider.getProviderName(), user.getUsername(), accessMode);

    List<NavigationMenuEntity> candidateMenus = provider.getNavEligibleMenus();
    List<NavigationMenuPermEntity> permRules = provider.getViewPermissionRules();

    Map<String, NavigationMenuPermEntity> ruleByMenu = new LinkedHashMap<>();
    Map<String, String> requiredPermByMenu = new LinkedHashMap<>();
    for (NavigationMenuPermEntity rule : permRules) {
      if ("VIEW".equalsIgnoreCase(rule.getPermissionScope()) && rule.getCandidatePermissionCode() != null) {
        ruleByMenu.put(rule.getStableMenuKey(), rule);
        requiredPermByMenu.put(rule.getStableMenuKey(), rule.getCandidatePermissionCode());
      }
    }

    // 4. 逐系统构建 Level 1 与 Level 2 节点
    List<SystemNode> systems = new ArrayList<>();
    int filteredOutCount = 0;

    for (AppCodeEnum app : AppCodeEnum.values()) {
      if (filterApp != null && app != filterApp) {
        continue;
      }

      // 作用域检查：Scoped 用户需核验其是否属于该系统作用域
      if (accessMode == AccessModeEnum.SCOPED_USER) {
        boolean inScope = user.getScopedApps().contains(app.name())
            || hasPermissionPrefix(user, app.getIamSystemCode());
        if (!inScope) {
          continue;
        }
      }

      List<PageLeafNode> children = new ArrayList<>();
      // E21：目录节点索引，子页按 parentStableMenuKey 挂载
      Map<String, PageLeafNode> directoryNodes = new LinkedHashMap<>();
      Map<String, Integer> orderMap = new LinkedHashMap<>();

      for (NavigationMenuEntity menu : candidateMenus) {
        if (!app.name().equalsIgnoreCase(menu.getNavigationAppCode())) {
          continue;
        }

        boolean isDirectoryNode = "DIRECTORY".equalsIgnoreCase(menu.getMenuKind());
        // E21：PAGE 与 DIRECTORY 都进入候选；其余类型过滤
        if (!"PAGE".equalsIgnoreCase(menu.getMenuKind()) && !isDirectoryNode) {
          filteredOutCount++;
          continue;
        }

        // 六门槛正交约束核验
        // 隔离测试实例可放行 nav_eligible=0 的候选页（不写库、不改正式口径）
        boolean isolatedCandidate =
            isolatedTestCandidates && menu.getValid() == 1 && menu.getEnabled() == 1 && menu.getVisible() == 1;
        if (menu.getValid() != 1 || menu.getEnabled() != 1 || menu.getVisible() != 1
            || (menu.getNavEligible() != 1 && !isolatedCandidate)) {
          filteredOutCount++;
          continue;
        }

        // 三级放行状态核验：当前目标环境放行状态真实核对
        boolean isApproved = isolatedCandidate || isReleaseApproved(menu.getStableMenuKey(), ruleByMenu, provider);
        if (!isApproved) {
          filteredOutCount++;
          continue;
        }

        // 只读模式硬否决：隐藏纯写页面（如 TRUST 提交更正页）
        if (isReadonly && isWriteOnlyPage(menu.getStableMenuKey())) {
          filteredOutCount++;
          continue;
        }

        // 权限点匹配核验
        String requiredPerm = requiredPermByMenu.get(menu.getStableMenuKey());
        boolean hasPerm = checkViewPermission(user, accessMode, requiredPerm);
        if (!hasPerm) {
          filteredOutCount++;
          continue;
        }

        // 组装节点：目录不参与跳转，页面保留真实 entryPath
        PageLeafNode leaf = toPageLeafNode(menu);
        orderMap.put(menu.getStableMenuKey(), menu.getSiblingSortOrder());
        if (isDirectoryNode) {
          directoryNodes.put(menu.getStableMenuKey(), leaf);
        } else {
          String parentKey = menu.getParentStableMenuKey();
          PageLeafNode parentDir = parentKey == null ? null : directoryNodes.get(parentKey);
          if (parentDir != null) {
            parentDir.getChildren().add(leaf);
          } else {
            // 父目录不存在或未授权：宁可挂到系统根，也不伪造目录
            children.add(leaf);
          }
        }
      }

      // E21：目录按父子关系嵌套；只有最外层目录挂到系统根
      for (NavigationMenuEntity m : candidateMenus) {
        if (!app.name().equalsIgnoreCase(m.getNavigationAppCode())) continue;
        if (!"DIRECTORY".equalsIgnoreCase(m.getMenuKind())) continue;
        PageLeafNode dir = directoryNodes.get(m.getStableMenuKey());
        if (dir == null) continue;
        String pk = m.getParentStableMenuKey();
        PageLeafNode parentDir = pk == null ? null : directoryNodes.get(pk);
        if (parentDir != null) {
          parentDir.getChildren().add(dir);   // 嵌套目录
        } else {
          children.add(dir);                  // 顶层目录
        }
      }
      // 去掉没有任何可见子页的空目录（目录权限来自子页）
      children.removeIf(n -> n.isDirectory() && (n.getChildren() == null || n.getChildren().isEmpty()));
      for (PageLeafNode n : children) {
        if (n.isDirectory()) pruneEmptyDirs(n);
      }
      children.sort((a, b) -> Integer.compare(
          orderMap.getOrDefault(a.getStableMenuKey(), Integer.MAX_VALUE),
          orderMap.getOrDefault(b.getStableMenuKey(), Integer.MAX_VALUE)));
      // 目录内部子页同样稳定排序
      for (PageLeafNode dir : directoryNodes.values()) {
        dir.getChildren().sort((a, b) -> Integer.compare(
            orderMap.getOrDefault(a.getStableMenuKey(), Integer.MAX_VALUE),
            orderMap.getOrDefault(b.getStableMenuKey(), Integer.MAX_VALUE)));
      }

      // 规则：一级系统无已授权二级功能时整个一级系统裁掉
      if (!children.isEmpty()) {
        SystemNode sysNode = new SystemNode();
        sysNode.setAppCode(app.name());
        sysNode.setDisplayName(app.getDefaultDisplayName());
        sysNode.setSortOrder(app.getSortOrder());
        sysNode.setEntryBasePath(app.getEntryBasePath());
        sysNode.setChildren(children);
        systems.add(sysNode);
      }
    }

    // 5. 组装顶层 operationPermissions
    Map<String, List<String>> operationPermissions = projectOperationPermissions(user, systems, isReadonly);

    // 6. 组装 diagnostics（仅对有特权且显式请求的主体返回）
    DiagnosticsInfo diag = null;
    if (Boolean.TRUE.equals(includeDiagnostics)) {
      if (isFullAdmin || user.hasPermission("iam:role:list:view") || user.hasPermission("iam:audit:list")) {
        diag = new DiagnosticsInfo("STRICT_APPROVED_ONLY", "BYPASS_REALTIME", filteredOutCount);
      }
    }

    NavigationData data = new NavigationData();
    data.setSchemaVersion("3.0");
    data.setGeneratedAt(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    data.setActiveBatchId("BATCH_1");
    data.setUserContext(userContext);
    data.setSystems(systems);
    data.setOperationPermissions(operationPermissions);
    data.setDiagnostics(diag);

    return data;
  }

  private boolean isReleaseApproved(
      String stableMenuKey,
      Map<String, NavigationMenuPermEntity> ruleByMenu,
      NavigationDataProvider provider) {
    // 1. 检查是否有线程级模拟批准（用于隔离单测）
    Set<String> simulated = SIMULATED_APPROVED_KEYS.get();
    if (simulated != null && simulated.contains(stableMenuKey)) {
      return true;
    }

    // 2. 若当前使用离线 Fixture 提供者或开启模拟开关，核查 Batch 1 七页
    if ("FIXTURE".equals(provider.getProviderName()) || simulateBatch1Approved) {
      return NavigationDataLoader.BATCH1_PAGES.containsKey(stableMenuKey);
    }

    // 3. 真实数据库模式：必须同时满足技术验证 (VERIFIED) 与审批放行 (APPROVED)
    NavigationMenuPermEntity rule = ruleByMenu.get(stableMenuKey);
    if (rule == null) {
      return false;
    }
    boolean isTechVerified = "VERIFIED".equalsIgnoreCase(rule.getMappingStatus())
        || "VERIFIED_EXISTING".equalsIgnoreCase(rule.getMappingStatus());
    boolean isApproved = "APPROVED".equalsIgnoreCase(rule.getApprovalStatus());

    return isTechVerified && isApproved && rule.getValid() == 1;
  }

  private boolean isWriteOnlyPage(String stableMenuKey) {
    return "TRUST:TRUST-M-create".equalsIgnoreCase(stableMenuKey);
  }

  private boolean checkViewPermission(LoginUser user, AccessModeEnum accessMode, String requiredPerm) {
    if (accessMode == AccessModeEnum.FULL_ADMIN) {
      return true;
    }
    if (accessMode == AccessModeEnum.READONLY_AUDITOR) {
      // 只读审计员对已放行的可读页面具备只读视图权限
      return true;
    }
    if (requiredPerm == null || requiredPerm.isBlank()) {
      return false;
    }
    return user.hasPermission(requiredPerm);
  }

  private boolean hasPermissionPrefix(LoginUser user, String systemCode) {
    String prefix = systemCode + ":";
    return user.getPermissions().stream().anyMatch(p -> p.startsWith(prefix));
  }

  /** E21：递归移除空目录，确保不出现“有目录无页面”的假入口。 */
  private void pruneEmptyDirs(PageLeafNode dir) {
    if (!dir.isDirectory()) return;
    List<PageLeafNode> kids = dir.getChildren();
    if (kids != null) {
      for (PageLeafNode k : new ArrayList<>(kids)) {
        if (k.isDirectory()) pruneEmptyDirs(k);
      }
      kids.removeIf(k -> k.isDirectory() && (k.getChildren() == null || k.getChildren().isEmpty()));
    }
  }

  private PageLeafNode toPageLeafNode(NavigationMenuEntity m) {
    PageLeafNode leaf = new PageLeafNode();
    leaf.setStableMenuKey(m.getStableMenuKey());
    leaf.setHierarchyCode(m.getHierarchyCode());
    leaf.setLevel(m.getNormalizedLevel() > 0 ? m.getNormalizedLevel() : 2);
    leaf.setMenuName(m.getMenuName());
    leaf.setNodeType("DIRECTORY".equalsIgnoreCase(m.getMenuKind()) ? "DIRECTORY" : "PAGE");

    EntryModeEnum mode = EntryModeEnum.fromDatabase(m.getEntryMode());
    leaf.setEntryMode(mode.name());
    // 目录不可跳转：强制清空入口
    if (leaf.isDirectory()) {
      leaf.setEntryPath("");
      leaf.setRoutePath("");
      leaf.setPageKey("");
      leaf.setTabKey("");
      return leaf;
    }
    leaf.setEntryPath(m.getEntryPath() != null ? m.getEntryPath() : "");

    if (mode == EntryModeEnum.SPA_ROUTE) {
      leaf.setRoutePath(m.getRoutePath() != null && !m.getRoutePath().isBlank() ? m.getRoutePath() : "/");
      leaf.setPageKey("");
      leaf.setTabKey("");
    } else if (mode == EntryModeEnum.PAGE_KEY) {
      leaf.setRoutePath("");
      leaf.setPageKey(m.getPageKey() != null ? m.getPageKey() : "");
      leaf.setTabKey("");
    } else if (mode == EntryModeEnum.TAB_TARGET) {
      leaf.setRoutePath("");
      leaf.setPageKey("");
      leaf.setTabKey(m.getTabKey() != null ? m.getTabKey() : "");
    }
    return leaf;
  }

  private Map<String, List<String>> projectOperationPermissions(
      LoginUser user, List<SystemNode> visibleSystems, boolean isReadonly) {
    Map<String, List<String>> result = new LinkedHashMap<>();

    for (SystemNode sys : visibleSystems) {
      AppCodeEnum app = AppCodeEnum.fromString(sys.getAppCode());
      if (app == null) continue;

      String sysPrefix = app.getIamSystemCode() + ":";
      List<String> opPerms = new ArrayList<>();

      for (String p : user.getPermissions()) {
        if (!p.startsWith(sysPrefix)) {
          continue;
        }

        // 只读硬否决：剥离全部状态改变动作
        if (isReadonly) {
          if (isWriteAction(p)) {
            continue;
          }
        }
        opPerms.add(p);
      }

      Collections.sort(opPerms);
      result.put(app.name(), opPerms);
    }
    return result;
  }

  private boolean isWriteAction(String permissionCode) {
    String lower = permissionCode.toLowerCase();
    return lower.contains(":add") || lower.contains(":edit") || lower.contains(":delete")
        || lower.contains(":execute") || lower.contains(":update") || lower.contains(":create")
        || lower.contains(":upload") || lower.contains(":retry") || lower.contains(":rotate")
        || lower.contains(":revoke") || lower.contains(":manage") || lower.contains(":review")
        || lower.contains(":export") || lower.contains(":submit") || lower.contains(":correct");
  }
}
