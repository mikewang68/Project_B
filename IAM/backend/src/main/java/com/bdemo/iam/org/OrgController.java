package com.bdemo.iam.org;

import com.bdemo.iam.common.R;
import com.bdemo.iam.org.domain.OrgNode;
import com.bdemo.iam.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/org")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping("/tree")
    @RequirePerm("iam:user:list:view")
    public R<List<OrgNode>> tree() {
        return R.ok(orgService.tree());
    }
}
