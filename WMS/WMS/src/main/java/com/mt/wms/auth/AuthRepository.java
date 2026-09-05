package com.mt.wms.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class AuthRepository {
    private final JdbcClient jdbc;

    public AuthRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LoginAccount> findLoginAccount(String company, String account) {
        return jdbc.sql("""
                        SELECT u.id AS user_id, c.id AS company_id, c.code AS company_code,
                               c.name AS company_name, u.username, u.display_name,
                               u.password_hash, u.password_change_required
                          FROM auth_user u
                          JOIN auth_company c ON c.id = u.company_id
                         WHERE (c.code = :company OR c.name = :company)
                           AND (u.username = :account OR u.mobile = :account)
                           AND c.status = 'ENABLED'
                           AND u.status = 'ENABLED'
                           AND u.login_enabled = TRUE
                         ORDER BY u.id
                         LIMIT 1
                        """)
                .param("company", company)
                .param("account", account)
                .query(LoginAccount.class)
                .optional();
    }

    List<String> findRoles(long userId) {
        return jdbc.sql("""
                        SELECT r.code FROM auth_role r
                        JOIN auth_user_role ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.sort_order, r.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list();
    }

    Set<String> findPermissions(long userId) {
        return jdbc.sql("""
                        SELECT DISTINCT p.code FROM auth_permission p
                        JOIN auth_role_permission rp ON rp.permission_id = p.id
                        JOIN auth_user_role ur ON ur.role_id = rp.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY p.code
                        """)
                .param("userId", userId)
                .query(String.class)
                .list().stream().collect(Collectors.toUnmodifiableSet());
    }

    List<AuthModels.MenuItem> findMenus(Set<String> permissions) {
        return jdbc.sql("""
                        SELECT code, parent_code, name, path, icon, required_permission, sort_order
                          FROM auth_menu
                         WHERE enabled = TRUE
                         ORDER BY sort_order, id
                        """)
                .query(MenuRow.class)
                .list().stream()
                .filter(menu -> menu.requiredPermission() == null || permissions.contains(menu.requiredPermission()))
                .map(menu -> new AuthModels.MenuItem(menu.code(), menu.parentCode(), menu.name(), menu.path(), menu.icon(), menu.sortOrder()))
                .toList();
    }

    List<AuthModels.TenantOption> findWarehouses(long userId, long companyId, boolean all) {
        String sql = all
                ? """
                  SELECT w.id, w.code, w.name FROM wms_warehouse w
                   WHERE w.company_id = :companyId AND w.status = 'ENABLED'
                   ORDER BY w.code
                  """
                : """
                  SELECT w.id, w.code, w.name FROM wms_warehouse w
                   JOIN auth_user_warehouse uw ON uw.warehouse_id = w.id
                   WHERE uw.user_id = :userId AND w.company_id = :companyId AND w.status = 'ENABLED'
                   ORDER BY w.code
                  """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("companyId", companyId);
        if (!all) {
            statement = statement.param("userId", userId);
        }
        return statement.query(AuthModels.TenantOption.class).list();
    }

    List<AuthModels.TenantOption> findOwners(long userId, long companyId, boolean all) {
        String sql = all
                ? """
                  SELECT o.id, o.code, o.name FROM wms_owner o
                   WHERE o.company_id = :companyId AND o.status = 'ENABLED'
                   ORDER BY o.code
                  """
                : """
                  SELECT o.id, o.code, o.name FROM wms_owner o
                   JOIN auth_user_owner uo ON uo.owner_id = o.id
                   WHERE uo.user_id = :userId AND o.company_id = :companyId AND o.status = 'ENABLED'
                   ORDER BY o.code
                  """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("companyId", companyId);
        if (!all) {
            statement = statement.param("userId", userId);
        }
        return statement.query(AuthModels.TenantOption.class).list();
    }

    void markLoginSuccess(long userId) {
        jdbc.sql("UPDATE auth_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = :userId")
                .param("userId", userId).update();
    }

    void updatePassword(long userId, String passwordHash, boolean requireChange) {
        jdbc.sql("""
                        UPDATE auth_user SET password_hash = :passwordHash,
                               password_change_required = :requireChange,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = :userId
                        """)
                .param("passwordHash", passwordHash)
                .param("requireChange", requireChange)
                .param("userId", userId)
                .update();
    }

    record LoginAccount(Long userId, Long companyId, String companyCode, String companyName,
                        String username, String displayName, String passwordHash,
                        boolean passwordChangeRequired) {
    }

    record MenuRow(String code, String parentCode, String name, String path, String icon,
                   String requiredPermission, int sortOrder) {
    }
}
