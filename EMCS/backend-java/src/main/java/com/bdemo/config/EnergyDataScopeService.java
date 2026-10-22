package com.bdemo.config;

import com.bdemo.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class EnergyDataScopeService {
    private final JdbcTemplate jdbc;

    public EnergyDataScopeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** REQ-073: dispatch is the demo's custom data-scope role and can only see AREA-A. */
    public String zone(Authentication authentication, String requestedZone) {
        String restricted=restrictedArea(authentication);
        if (restricted!=null) {
            String code=restricted.replace("AREA-", "");
            if(requestedZone!=null && !"ALL".equalsIgnoreCase(requestedZone) && !code.equalsIgnoreCase(requestedZone))
                throw new BusinessException(403,"当前身份不可访问该区域");
            return code;
        }
        if (hasRole(authentication, "dispatch")) {
            if (requestedZone != null && !"ALL".equalsIgnoreCase(requestedZone)
                    && !"A".equalsIgnoreCase(requestedZone)) {
                throw new BusinessException(403, "当前角色仅可访问 A 区数据");
            }
            return "A";
        }
        return requestedZone;
    }

    public void checkEquipment(Authentication authentication, String equipmentCode) {
        String restricted=restrictedArea(authentication);
        if (restricted==null && !hasRole(authentication, "dispatch")) return;
        if (restricted==null) restricted="AREA-A";
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM e_equipment e JOIN e_area a ON a.area_id=e.area_id
                WHERE e.equipment_code=? AND a.area_code=?
                """, Integer.class, equipmentCode,restricted);
        if (count == null || count == 0) throw new BusinessException(403, "当前身份不可访问该资源所属区域");
    }

    public void checkAlert(Authentication authentication, long eventId) {
        String restricted=restrictedArea(authentication);
        if (restricted==null && !hasRole(authentication, "dispatch")) return;
        if (restricted==null) restricted="AREA-A";
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM e_alert_event e LEFT JOIN e_area a ON a.area_id=e.area_id
                WHERE e.event_id=? AND a.area_code=?
                """, Integer.class, eventId,restricted);
        if (count == null || count == 0) throw new BusinessException(403, "当前身份不可访问该资源所属区域");
    }

    private String restrictedArea(Authentication authentication){
        if(authentication!=null && authentication.getDetails() instanceof com.bdemo.auth.sso.EmcsSsoAccess access && access.areaRestricted()) {
            if(hasRole(authentication,"dispatch") && !"AREA-A".equals(access.scopeArea()))
                throw new BusinessException(403,"SSO范围与本地角色范围不一致");
            return access.scopeArea();
        }
        return null;
    }
    private boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }
}
