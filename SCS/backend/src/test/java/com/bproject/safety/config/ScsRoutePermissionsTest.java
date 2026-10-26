package com.bproject.safety.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由 → 权限码映射的契约测试。
 *
 * <p>背景：{@link ScsRoutePermissions} 此前零覆盖，导致 3 个后端强制要求的权限码
 * 不在 IAM 目录里、1 个写接口映射为 null 回落绑定文件（无条目即 403），
 * 非 ADMIN 账号在围栏编辑、移动端接单、统计突增、首页演示开关上恒 403。
 *
 * <p>本测试锁定两件事：
 * <ol>
 *   <li>每个受保护路由都能解析出具体权限码（不得为 null）；</li>
 *   <li>源码里出现的每个 safety:* 权限码都在 IAM 迁移目录中登记（防止再次漂移）。</li>
 * </ol>
 */
@DisplayName("SCS 路由权限映射契约")
class ScsRoutePermissionsTest {

  @Test
  @DisplayName("围栏写操作要求 safety:fence:edit（此前缺失于 IAM 目录）")
  void fenceWritesRequireEdit() {
    assertEquals("safety:fence:edit", ScsRoutePermissions.required("POST", "/api/v1/fences"));
    assertEquals("safety:fence:edit", ScsRoutePermissions.required("PUT", "/api/v1/fences/F-1"));
    assertEquals("safety:fence:edit", ScsRoutePermissions.required("POST", "/api/v1/fences/F-1/disable"));
    assertEquals("safety:fence:edit", ScsRoutePermissions.required("POST", "/api/v1/fences/F-1/submit-review"));
    assertEquals("safety:fence:edit", ScsRoutePermissions.required("POST", "/api/v1/fences/F-1/simulate-mismatch"));
    assertEquals("safety:fence:publish", ScsRoutePermissions.required("POST", "/api/v1/fences/F-1/publish"));
    assertEquals("safety:fence:list:view", ScsRoutePermissions.required("GET", "/api/v1/fences"));
  }

  @Test
  @DisplayName("移动端接单/到场要求 safety:mobile:execute（此前缺失于 IAM 目录）")
  void mobileActionsRequireExecute() {
    assertEquals("safety:mobile:execute",
        ScsRoutePermissions.required("POST", "/api/v1/mobile/incidents/A-1/accept"));
    assertEquals("safety:mobile:execute",
        ScsRoutePermissions.required("POST", "/api/v1/mobile/incidents/A-1/arrive"));
    assertEquals("safety:mobile:view", ScsRoutePermissions.required("GET", "/api/v1/mobile/home"));
  }

  @Test
  @DisplayName("统计突增要求 safety:module:stat:execute（此前缺失于 IAM 目录）")
  void analyticsSurgeRequiresExecute() {
    assertEquals("safety:module:stat:execute",
        ScsRoutePermissions.required("POST", "/api/v1/analytics/simulate-surge"));
    assertEquals("safety:module:stat:view", ScsRoutePermissions.required("GET", "/api/v1/analytics/dataset"));
  }

  @Test
  @DisplayName("首页演示开关不再回落绑定文件（此前为 null → 非 ADMIN 恒 403）")
  void overviewSimulateRiskHasExplicitPermission() {
    assertEquals("safety:home:map:edit",
        ScsRoutePermissions.required("POST", "/api/v1/overview/simulate-risk"));
    assertEquals("safety:home:map:view", ScsRoutePermissions.required("GET", "/api/v1/overview/summary"));
  }

  @Test
  @DisplayName("AI / 告警 / 规则 / 碰撞 / 运维 的读写权限分流正确")
  void modulePermissionsSplitByOperation() {
    assertEquals("safety:module:ai:view", ScsRoutePermissions.required("GET", "/api/v1/ai-events"));
    assertEquals("safety:module:ai:edit", ScsRoutePermissions.required("POST", "/api/v1/ai-events/AI-1/confirm"));
    assertEquals("safety:module:ai:assign", ScsRoutePermissions.required("POST", "/api/v1/ai-events/AI-1/assign"));
    assertEquals("safety:module:ai:model:view", ScsRoutePermissions.required("GET", "/api/v1/ai/models"));
    assertEquals("safety:module:ai:model:execute", ScsRoutePermissions.required("POST", "/api/v1/ai/models/M-1/infer"));

    assertEquals("safety:module:alert:view", ScsRoutePermissions.required("GET", "/api/v1/alerts"));
    assertEquals("safety:module:alert:handle", ScsRoutePermissions.required("POST", "/api/v1/alerts/A-1/confirm"));
    assertEquals("safety:module:alert:approve", ScsRoutePermissions.required("POST", "/api/v1/alerts/A-1/review"));

    assertEquals("safety:module:rule:approve", ScsRoutePermissions.required("POST", "/api/v1/rules/R-1/approve"));
    assertEquals("safety:module:rule:execute", ScsRoutePermissions.required("POST", "/api/v1/rules/R-1/publish"));

    assertEquals("safety:module:collision:execute", ScsRoutePermissions.required("POST", "/api/v1/collision/takeover"));
    assertEquals("safety:module:ops:execute", ScsRoutePermissions.required("POST", "/api/v1/ops/simulate"));
  }

  @Test
  @DisplayName("非 /api/v1 路径不参与权限映射（由拦截器其它分支处理）")
  void nonApiPathsAreUnmapped() {
    assertEquals(null, ScsRoutePermissions.required("GET", "/health/ready"));
    assertEquals(null, ScsRoutePermissions.required("GET", "/actuator/prometheus"));
  }

  @Test
  @DisplayName("源码里出现的每个 safety:* 权限码都必须在 IAM 迁移目录登记")
  void everyReferencedCodeIsRegisteredInIamCatalog() throws IOException {
    Set<String> registered = new TreeSet<>();
    try (Stream<Path> files = Files.walk(iamIsolatedDir())) {
      for (Path p : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
        Matcher m = Pattern.compile("'(safety:[a-z:]+)'")
            .matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (m.find()) {
          registered.add(m.group(1));
        }
      }
    }
    assertTrue(!registered.isEmpty(), "未在 IAM 迁移目录找到任何 safety:* 权限码（路径解析可能失效）");

    Set<String> referenced = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(scsConfigDir())) {
      for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        // 只收"完整权限码"：至少两段，且以 view/edit/export/... 等操作段结尾。
        // 模块前缀常量（如 "safety:fence"）是拼接用的半成品，不参与登记比对。
        Matcher m = Pattern.compile(
                "\"(safety:[a-z:]+:(?:view|edit|execute|export|approve|handle|assign|publish|read))\"")
            .matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (m.find()) {
          referenced.add(m.group(1));
        }
      }
    }

    Set<String> missing = new TreeSet<>(referenced);
    missing.removeAll(registered);
    assertTrue(missing.isEmpty(),
        "以下权限码在 SCS 代码中被强制要求，但 IAM 目录未登记（IAM 无法授予，非 ADMIN 会恒 403）：" + missing);
  }

  @Test
  @DisplayName("分组权限码与左侧菜单 CSV 一致")
  void groupPermissionsMatchMenuCsv() throws IOException {
    Path csv = scsRoot().resolve("docs/scs_module_menu.csv");
    assertTrue(Files.exists(csv), "找不到菜单定义文件: " + csv);
    String text = Files.readString(csv, StandardCharsets.UTF_8);
    Set<String> registered = new LinkedHashSet<>();
    try (Stream<Path> files = Files.walk(iamIsolatedDir())) {
      for (Path p : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
        Matcher matcher = Pattern.compile("'(safety:[a-z:]+)'")
            .matcher(Files.readString(p, StandardCharsets.UTF_8));
        while (matcher.find()) registered.add(matcher.group(1));
      }
    }
    for (String code : Set.of("safety:work:view", "safety:event:view", "safety:sys:view")) {
      assertTrue(text.contains(code), "菜单 CSV 缺少分组权限码: " + code);
      assertTrue(registered.contains(code), "IAM 目录缺少菜单分组权限码: " + code);
    }
  }

  // ---------- 路径定位：测试运行目录是 backend/ ----------

  private static Path scsRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("docs/scs_module_menu.csv"))) {
      p = p.getParent();
    }
    assertNotNull(p, "无法定位 SCS 根目录（未找到 docs/scs_module_menu.csv）");
    return p;
  }

  private static Path iamIsolatedDir() throws IOException {
    // SCS/backend → SCS → Project_B 根 → IAM/backend/db/isolated
    Path scs = scsRoot();
    Path iam = scs.getParent().resolve("IAM/backend/db/isolated");
    assertTrue(Files.isDirectory(iam), "找不到 IAM 迁移目录: " + iam);
    return iam;
  }

  private static Path scsConfigDir() {
    return scsRoot().resolve("backend/src/main/java/com/bproject/safety/config");
  }
}
