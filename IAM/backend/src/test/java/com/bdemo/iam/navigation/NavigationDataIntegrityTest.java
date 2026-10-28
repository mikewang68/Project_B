package com.bdemo.iam.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bdemo.iam.navigation.enums.AppCodeEnum;
import com.bdemo.iam.navigation.enums.EntryModeEnum;
import com.bdemo.iam.navigation.service.NavigationDataLoader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 阶段 2-B 数据完整性与基准对账测试。
 * 验证八系统固定顺序、187 条规范化菜单记录逐项对账、Batch 1 七页精确匹配及 100 待确认页边界。
 */
public class NavigationDataIntegrityTest {

  @Test
  void test_01_eight_systems_fixed_enum_order_and_no_dts() {
    AppCodeEnum[] apps = AppCodeEnum.values();
    assertEquals(8, apps.length, "必须严格为八系统枚举");

    assertEquals("WMS", apps[0].name());
    assertEquals(10, apps[0].getSortOrder());
    assertEquals("/wms/", apps[0].getEntryBasePath());
    assertEquals("wms", apps[0].getIamSystemCode());

    assertEquals("PSMS", apps[1].name());
    assertEquals(20, apps[1].getSortOrder());
    assertEquals("/psms/", apps[1].getEntryBasePath());
    assertEquals("pps", apps[1].getIamSystemCode()); // 严禁误映射为 psms

    assertEquals("SCS", apps[2].name());
    assertEquals(30, apps[2].getSortOrder());
    assertEquals("/scs/", apps[2].getEntryBasePath());
    assertEquals("safety", apps[2].getIamSystemCode()); // 严禁误映射为 scs
    assertEquals("安全卡控系统", apps[2].getDefaultDisplayName()); // 门户显示名以枚举为准

    assertEquals("EHMS", apps[3].name());
    assertEquals(40, apps[3].getSortOrder());
    assertEquals("/ehm/", apps[3].getEntryBasePath());
    assertEquals("ehealth", apps[3].getIamSystemCode()); // 严禁误映射为 ehm

    assertEquals("EMCS", apps[4].name());
    assertEquals(50, apps[4].getSortOrder());
    assertEquals("/emcs/", apps[4].getEntryBasePath());
    assertEquals("ems", apps[4].getIamSystemCode()); // 严禁误映射为 emcs

    assertEquals("TRUST", apps[5].name());
    assertEquals(60, apps[5].getSortOrder());
    assertEquals("/trust/", apps[5].getEntryBasePath());
    assertEquals("trust", apps[5].getIamSystemCode());

    assertEquals("IAM", apps[6].name());
    assertEquals(70, apps[6].getSortOrder());
    assertEquals("/iam/", apps[6].getEntryBasePath());
    assertEquals("iam", apps[6].getIamSystemCode());

    assertEquals("SYS", apps[7].name());
    assertEquals(80, apps[7].getSortOrder());
    assertEquals("/sys/", apps[7].getEntryBasePath());
    assertEquals("sys", apps[7].getIamSystemCode());

    // 验证 DTS 绝不属于 AppCodeEnum
    assertNull(AppCodeEnum.fromString("DTS"));
    assertNull(AppCodeEnum.fromString("dt"));
  }

  @Test
  void test_02_batch1_seven_pages_exact_matching() {
    Map<String, NavigationDataLoader.Batch1PageMeta> b1 = NavigationDataLoader.BATCH1_PAGES;
    assertEquals(7, b1.size(), "Batch 1 必须严格且仅有 7 个候选页 (IAM 4 + SYS 3)");

    // IAM 4 页
    assertTrue(b1.containsKey("IAM:users"));
    assertEquals("IAM.01", b1.get("IAM:users").hierarchyCode());
    assertEquals("/system/users", b1.get("IAM:users").routePath());
    assertEquals("iam:user:list:view", b1.get("IAM:users").requiredPermCode());

    assertTrue(b1.containsKey("IAM:roles"));
    assertEquals("IAM.02", b1.get("IAM:roles").hierarchyCode());
    assertEquals("/system/roles", b1.get("IAM:roles").routePath());
    assertEquals("iam:role:list:view", b1.get("IAM:roles").requiredPermCode());

    assertTrue(b1.containsKey("IAM:menus"));
    assertEquals("IAM.03", b1.get("IAM:menus").hierarchyCode());
    assertEquals("/system/menus", b1.get("IAM:menus").routePath());
    assertEquals("iam:menu:tree:view", b1.get("IAM:menus").requiredPermCode());

    assertTrue(b1.containsKey("IAM:health"));
    assertEquals("IAM.04", b1.get("IAM:health").hierarchyCode());
    assertEquals("/system/health", b1.get("IAM:health").routePath());
    assertEquals("iam:role:list:view", b1.get("IAM:health").requiredPermCode());

    // SYS 3 页
    assertTrue(b1.containsKey("SYS:dict"));
    assertEquals("SYS.01", b1.get("SYS:dict").hierarchyCode());
    assertEquals("/maintain/dict", b1.get("SYS:dict").routePath());
    assertEquals("sys:dict:type:view", b1.get("SYS:dict").requiredPermCode());

    assertTrue(b1.containsKey("SYS:logs"));
    assertEquals("SYS.02", b1.get("SYS:logs").hierarchyCode());
    assertEquals("/maintain/logs", b1.get("SYS:logs").routePath());
    assertEquals("sys:log:list:view", b1.get("SYS:logs").requiredPermCode());

    assertTrue(b1.containsKey("SYS:config"));
    assertEquals("SYS.03", b1.get("SYS:config").hierarchyCode());
    assertEquals("/maintain/config", b1.get("SYS:config").routePath());
    assertEquals("sys:config:list:view", b1.get("SYS:config").requiredPermCode());
  }

  @Test
  void test_03_reconciliation_against_normalized_csv_187_records() throws Exception {
    File csvFile = new File("D:\\智慧仓储项目\\tmp\\stage1_menu_normalization\\outputs\\navigation_menu_normalized.csv");
    org.junit.jupiter.api.Assumptions.assumeTrue(csvFile.exists(),
        "SKIP: 阶段 1 标准化 CSV 仅存在于开发机，CI/服务器环境跳过该对账测试");

    List<Map<String, String>> rows = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
      String headerLine = reader.readLine();
      assertNotNull(headerLine);
      if (headerLine.startsWith("\uFEFF")) {
        headerLine = headerLine.substring(1);
      }
      String[] headers = headerLine.split(",");

      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.length && i < parts.length; i++) {
          row.put(headers[i].trim(), parts[i].trim().replaceAll("^\"|\"$", ""));
        }
        rows.add(row);
      }
    }

    assertEquals(187, rows.size(), "标准化 CSV 必须严格包含 187 条菜单记录");

    Map<String, Integer> kindCount = new HashMap<>();
    Map<String, Integer> perAppCandidateCount = new HashMap<>();
    int navEligibleTotal = 0;

    for (Map<String, String> r : rows) {
      String kind = r.get("menu_kind");
      kindCount.put(kind, kindCount.getOrDefault(kind, 0) + 1);

      String eligible = r.get("nav_eligible");
      if ("TRUE".equalsIgnoreCase(eligible) || "1".equals(eligible)) {
        navEligibleTotal++;
        String app = r.get("navigation_app_code");
        perAppCandidateCount.put(app, perAppCandidateCount.getOrDefault(app, 0) + 1);
      }
    }

    // 分类断言
    assertEquals(8, kindCount.get("APP").intValue());
    assertEquals(22, kindCount.get("DIRECTORY").intValue());
    assertEquals(140, kindCount.get("PAGE").intValue());
    assertEquals(15, kindCount.get("AUXILIARY").intValue());
    assertEquals(1, kindCount.get("DRAWER").intValue());
    assertEquals(1, kindCount.get("PLANNED").intValue());

    // 静态导航候选页总数断言
    assertEquals(107, navEligibleTotal);

    // 逐系统候选页断言：WMS 7, PSMS 11, SCS 9, EHMS 55, EMCS 12, TRUST 6, IAM 4, SYS 3
    assertEquals(7, perAppCandidateCount.get("WMS").intValue());
    assertEquals(11, perAppCandidateCount.get("PSMS").intValue());
    assertEquals(9, perAppCandidateCount.get("SCS").intValue());
    assertEquals(55, perAppCandidateCount.get("EHMS").intValue());
    assertEquals(12, perAppCandidateCount.get("EMCS").intValue());
    assertEquals(6, perAppCandidateCount.get("TRUST").intValue());
    assertEquals(4, perAppCandidateCount.get("IAM").intValue());
    assertEquals(3, perAppCandidateCount.get("SYS").intValue());

    // 证明：IAM 4 + SYS 3 = 7，其余六系统 107 - 7 = 100
    int batch1Count = perAppCandidateCount.get("IAM") + perAppCandidateCount.get("SYS");
    assertEquals(7, batch1Count);
    assertEquals(100, navEligibleTotal - batch1Count);
  }
}
