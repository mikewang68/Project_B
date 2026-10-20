package com.bdemo.iam.controller;

import com.bdemo.iam.common.R;
import com.bdemo.iam.navigation.dto.NavigationData;
import com.bdemo.iam.navigation.service.NavigationProjectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 2-B IAM 两级动态授权导航树 API 控制器。
 *
 * 挂载路径：/api/v1/iam/navigation/tree (由 server.servlet.context-path /api/v1/iam + /navigation/tree 组合而成)
 * 遵循严格契约：iam_navigation_api_contract_v3 与 iam_navigation_response_schema_v3.json。
 */
@RestController
@RequestMapping("/navigation")
public class NavigationController {

  private final NavigationProjectionService projectionService;

  public NavigationController(NavigationProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  @GetMapping("/tree")
  public R<NavigationData> tree(
      @RequestParam(required = false) String appCode,
      @RequestParam(required = false, defaultValue = "false") Boolean includeDiagnostics) {
    return R.ok(projectionService.projectNavigationTree(appCode, includeDiagnostics));
  }
}
