package com.bdemo.iam.health;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限健康诊断入口。仅具备 IAM 角色/权限查看权限的管理员可访问（复用现有 permissionCode）。
 */
@RestController
@RequestMapping("/health")
public class PermissionHealthController {

    private final PermissionHealthService permissionHealthService;

    public PermissionHealthController(PermissionHealthService permissionHealthService) {
        this.permissionHealthService = permissionHealthService;
    }

    @GetMapping("/permissions")
    @RequirePerm("iam:role:list:view")
    public R<PermissionHealthReport> report() {
        return R.ok(permissionHealthService.analyze());
    }
}
