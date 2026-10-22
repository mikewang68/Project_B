package com.bdemo.iam.navigation.mapper;

import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NavigationMenuMapper {

  @Select("""
      SELECT app_code AS appCode, iam_system_code AS iamSystemCode,
             portal_module_key AS portalModuleKey, display_name AS displayName,
             sort_order AS sortOrder, entry_base_path AS entryBasePath,
             domain_name AS domainName, public_scheme AS publicScheme, public_port AS publicPort,
             origin_scheme AS originScheme, origin_ip AS originIp, origin_port AS originPort,
             module_path AS modulePath,
             in_nav_scope AS inNavScope, enabled, valid,
             mapping_status AS mappingStatus, approval_status AS approvalStatus
      FROM iam.iam_navigation_app
      WHERE valid = 1 AND in_nav_scope = 1
      ORDER BY sort_order ASC
      """)
  List<NavigationAppEntity> selectActiveApps();

  @Select("""
      <script>
      SELECT stable_menu_key AS stableMenuKey, navigation_app_code AS navigationAppCode,
             source_app_code AS sourceAppCode, source_menu_id AS sourceMenuId,
             source_parent_id AS sourceParentId, parent_stable_menu_key AS parentStableMenuKey,
             hierarchy_code AS hierarchyCode, original_level AS originalLevel,
             normalized_level AS normalizedLevel, sibling_sort_order AS siblingSortOrder,
             menu_name AS menuName, projected_display_title AS projectedDisplayTitle,
             menu_kind AS menuKind, entry_mode AS entryMode, route_path AS routePath,
             entry_path AS entryPath, page_key AS pageKey, tab_key AS tabKey,
             component_path AS componentPath, icon, visible, enabled, valid,
             nav_eligible AS navEligible, source_permission_raw AS sourcePermissionRaw
      FROM iam.iam_navigation_menu
      WHERE valid = 1 AND enabled = 1 AND visible = 1
      <if test="includeCandidates == null or includeCandidates == false">
        AND nav_eligible = 1
      </if>
      ORDER BY navigation_app_code ASC, normalized_level ASC, sibling_sort_order ASC
      </script>
      """)
  List<NavigationMenuEntity> selectNavEligibleMenus(@Param("includeCandidates") Boolean includeCandidates);

  @Select("""
      SELECT mapping_id AS mappingId, stable_menu_key AS stableMenuKey,
             navigation_app_code AS navigationAppCode, permission_scope AS permissionScope,
             rule_operator AS ruleOperator, source_permission_raw AS sourcePermissionRaw,
             candidate_permission_code AS candidatePermissionCode,
             existing_iam_permission_id AS existingIamPermissionId,
             existing_iam_system_code AS existingIamSystemCode,
             mapping_status AS mappingStatus, approval_status AS approvalStatus, valid
      FROM iam.iam_navigation_menu_permission
      WHERE valid = 1 AND permission_scope = 'VIEW'
      """)
  List<NavigationMenuPermEntity> selectViewPermissionRules();

  @Select("""
      SELECT mapping_id AS mappingId, stable_menu_key AS stableMenuKey,
             navigation_app_code AS navigationAppCode, permission_scope AS permissionScope,
             rule_operator AS ruleOperator, source_permission_raw AS sourcePermissionRaw,
             candidate_permission_code AS candidatePermissionCode,
             existing_iam_permission_id AS existingIamPermissionId,
             existing_iam_system_code AS existingIamSystemCode,
             mapping_status AS mappingStatus, approval_status AS approvalStatus, valid
      FROM iam.iam_navigation_menu_permission
      WHERE valid = 1 AND permission_scope = 'OPERATION_REF'
      """)
  List<NavigationMenuPermEntity> selectOperationPermissionRules();
}
