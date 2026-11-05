package com.bdemo.iam.scope;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Registration and capability are necessary; address publication and service identity remain separate gates. */
@Service
public class ApplicationRegistry {
  private final JdbcTemplate db;private final ScopeContextService contexts;
  public ApplicationRegistry(JdbcTemplate db,ScopeContextService contexts){this.db=db;this.contexts=contexts;}
  public boolean accepts(String audience) {
    if(audience==null||!audience.matches("[A-Za-z0-9_-]{1,32}"))return false;
    if(!contexts.enabled())return com.bdemo.iam.sso.SsoTicketService.ALLOWED_AUDIENCE.contains(audience.toLowerCase(Locale.ROOT));
    return db.queryForObject("SELECT count(*) FROM iam.iam_navigation_app WHERE app_code=? AND enabled=1 AND valid=1 AND in_nav_scope=1 AND (trim(domain_name)<>'' OR trim(origin_ip)<>'')",Integer.class,audience.toUpperCase(Locale.ROOT))==1;
  }
  public boolean registered(String audience) {
    if(audience==null||audience.isEmpty()||audience.length()>32
        ||!audience.chars().allMatch(c->c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='-'))return false;
    if(!contexts.enabled())return accepts(audience);
    return db.queryForObject("SELECT count(*) FROM iam.iam_navigation_app WHERE app_code=? AND valid=1",Integer.class,audience.toUpperCase(Locale.ROOT))==1;
  }
  public int contract(String audience) {
    if(!contexts.enabled())return 1;
    var result=db.queryForList("SELECT context_contract_max FROM iam.iam_navigation_app WHERE app_code=? AND enabled=1 AND valid=1",Integer.class,audience.toUpperCase(Locale.ROOT));
    return result.isEmpty()?0:result.get(0);
  }
  public String permissionSystem(String audience) {
    if(!contexts.enabled()) {
      var legacy=com.bdemo.iam.navigation.enums.AppCodeEnum.fromString(audience);
      return legacy==null?null:legacy.getIamSystemCode();
    }
    var result=db.queryForList("SELECT iam_system_code FROM iam.iam_navigation_app WHERE app_code=? AND enabled=1 AND valid=1",String.class,audience.toUpperCase(Locale.ROOT));
    return result.isEmpty()?null:result.get(0);
  }
  public Set<String> audiences() {
    if(!contexts.enabled())return new TreeSet<>(com.bdemo.iam.sso.SsoTicketService.ALLOWED_AUDIENCE);
    return new TreeSet<>(db.queryForList("SELECT lower(app_code) FROM iam.iam_navigation_app WHERE enabled=1 AND valid=1 AND in_nav_scope=1",String.class));
  }
}
