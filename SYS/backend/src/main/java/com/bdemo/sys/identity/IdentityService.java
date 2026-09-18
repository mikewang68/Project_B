package com.bdemo.sys.identity;

import com.bdemo.sys.identity.mapper.IamIdentityMapper;
import com.bdemo.sys.security.LoginUser;
import com.bdemo.sys.security.LoginUserLoader;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * SYS 身份加载：JWT 由 IAM 签发，SYS 本地验签后跨 schema 读取 IAM 用户/角色/权限。
 */
@Service
public class IdentityService implements LoginUserLoader {

    private final IamIdentityMapper mapper;

    public IdentityService(IamIdentityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public LoginUser load(String userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> user = mapper.selectUser(userId);
        if (user == null || !"active".equals(String.valueOf(user.get("status")))) {
            return null;
        }
        List<Map<String, Object>> roles = mapper.selectRoles(userId);
        boolean superAdmin = roles.stream()
                .anyMatch(r -> "super_admin".equals(String.valueOf(r.get("code"))));
        List<String> codes = mapper.selectPermissionCodes(userId);
        return new LoginUser(
                String.valueOf(user.get("id")),
                String.valueOf(user.get("username")),
                String.valueOf(user.get("displayName")),
                superAdmin,
                new HashSet<>(codes));
    }

    public List<Map<String, Object>> loadRoles(String userId) {
        return mapper.selectRoles(userId);
    }
}
