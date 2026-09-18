package com.bdemo.iam.permission;

import com.bdemo.iam.common.R;
import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping("/tree")
    @RequirePerm("iam:menu:tree:view")
    public R<List<PermissionNode>> tree() {
        return R.ok(permissionService.tree());
    }
}
