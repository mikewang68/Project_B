package com.mt.wms.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class UserAdminRepository {
    private final JdbcClient jdbc;

    UserAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<UserRow> findUsers(long companyId) {
        return jdbc.sql("""
                SELECT id, username, mobile, display_name, status, login_enabled, password_change_required
                  FROM auth_user WHERE company_id = :companyId AND status <> 'DELETED'
                 ORDER BY id
                """).param("companyId", companyId).query(UserRow.class).list();
    }

    Optional<UserRow> findUser(long companyId, long userId) {
        return jdbc.sql("""
                SELECT id, username, mobile, display_name, status, login_enabled, password_change_required
                  FROM auth_user WHERE company_id = :companyId AND id = :userId AND status <> 'DELETED'
                """).param("companyId", companyId).param("userId", userId).query(UserRow.class).optional();
    }

    List<String> roles(long userId) {
        return jdbc.sql("SELECT r.code FROM auth_role r JOIN auth_user_role ur ON ur.role_id=r.id WHERE ur.user_id=:id ORDER BY r.sort_order")
                .param("id", userId).query(String.class).list();
    }

    List<String> warehouses(long userId) {
        return jdbc.sql("SELECT w.code FROM wms_warehouse w JOIN auth_user_warehouse uw ON uw.warehouse_id=w.id WHERE uw.user_id=:id ORDER BY w.code")
                .param("id", userId).query(String.class).list();
    }

    List<String> owners(long userId) {
        return jdbc.sql("SELECT o.code FROM wms_owner o JOIN auth_user_owner uo ON uo.owner_id=o.id WHERE uo.user_id=:id ORDER BY o.code")
                .param("id", userId).query(String.class).list();
    }

    List<UserAdminModels.RoleOption> roles() {
        return jdbc.sql("SELECT code, name FROM auth_role ORDER BY sort_order").query(UserAdminModels.RoleOption.class).list();
    }

    long create(long companyId, UserAdminModels.SaveUserRequest request, String passwordHash) {
        return jdbc.sql("""
                INSERT INTO auth_user(company_id, username, mobile, display_name, password_hash,
                                      status, login_enabled, password_change_required)
                VALUES (:companyId, :username, :mobile, :displayName, :passwordHash, 'ENABLED', TRUE, TRUE)
                RETURNING id
                """).param("companyId", companyId).param("username", request.username().trim())
                .param("mobile", blankToNull(request.mobile())).param("displayName", request.displayName().trim())
                .param("passwordHash", passwordHash).query(Long.class).single();
    }

    int update(long companyId, long userId, UserAdminModels.SaveUserRequest request) {
        return jdbc.sql("""
                UPDATE auth_user SET mobile=:mobile, display_name=:displayName, status=:status,
                       login_enabled=:loginEnabled, updated_at=CURRENT_TIMESTAMP
                 WHERE company_id=:companyId AND id=:userId AND status <> 'DELETED'
                """).param("mobile", blankToNull(request.mobile())).param("displayName", request.displayName().trim())
                .param("status", normalizeStatus(request.status())).param("loginEnabled", request.loginEnabled() == null || request.loginEnabled())
                .param("companyId", companyId).param("userId", userId).update();
    }

    void replaceAssignments(long companyId, long userId, UserAdminModels.SaveUserRequest request) {
        jdbc.sql("DELETE FROM auth_user_role WHERE user_id=:id").param("id", userId).update();
        for (String code : request.roleCodes()) {
            int count = jdbc.sql("INSERT INTO auth_user_role(user_id,role_id) SELECT :id,id FROM auth_role WHERE code=:code")
                    .param("id", userId).param("code", code).update();
            if (count != 1) throw new IllegalArgumentException("角色不存在：" + code);
        }
        jdbc.sql("DELETE FROM auth_user_warehouse WHERE user_id=:id").param("id", userId).update();
        for (String code : request.warehouseCodes()) {
            int count = jdbc.sql("INSERT INTO auth_user_warehouse(user_id,warehouse_id) SELECT :id,id FROM wms_warehouse WHERE company_id=:companyId AND code=:code AND status='ENABLED'")
                    .param("id", userId).param("companyId", companyId).param("code", code).update();
            if (count != 1) throw new IllegalArgumentException("仓库不存在或不可用：" + code);
        }
        jdbc.sql("DELETE FROM auth_user_owner WHERE user_id=:id").param("id", userId).update();
        for (String code : request.ownerCodes()) {
            int count = jdbc.sql("INSERT INTO auth_user_owner(user_id,owner_id) SELECT :id,id FROM wms_owner WHERE company_id=:companyId AND code=:code AND status='ENABLED'")
                    .param("id", userId).param("companyId", companyId).param("code", code).update();
            if (count != 1) throw new IllegalArgumentException("货主不存在或不可用：" + code);
        }
    }

    void updatePassword(long companyId, long userId, String hash) {
        int count = jdbc.sql("UPDATE auth_user SET password_hash=:hash,password_change_required=TRUE,updated_at=CURRENT_TIMESTAMP WHERE company_id=:companyId AND id=:id")
                .param("hash", hash).param("companyId", companyId).param("id", userId).update();
        if (count != 1) throw new IllegalArgumentException("用户不存在");
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeStatus(String value) { return "DISABLED".equals(value) ? "DISABLED" : "ENABLED"; }

    record UserRow(Long id, String username, String mobile, String displayName, String status,
                   boolean loginEnabled, boolean passwordChangeRequired) {}
}
