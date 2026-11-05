package com.bdemo.iam.sso;

import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.security.LoginUserLoader;
import com.bdemo.iam.security.mapper.SessionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/** Current authorization, never a permission snapshot supplied by the browser. */
@Service
public class SsoAuthorizationService {
  private final SessionMapper sessions;
  private final LoginUserLoader users;
  private final SsoSubjectGuard subjects;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.bdemo.iam.scope.ApplicationRegistry registry;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private com.bdemo.iam.scope.ScopeContextService scopeContexts;

  public SsoAuthorizationService(SessionMapper sessions, LoginUserLoader users, SsoSubjectGuard subjects) {
    this.sessions = sessions;
    this.users = users;
    this.subjects = subjects;
  }

  public Map<String, Object> current(String subject, String sid, String audience) {
    String aud = audience == null ? "" : audience.trim().toLowerCase(java.util.Locale.ROOT);
    if (!(registry==null?SsoTicketService.ALLOWED_AUDIENCE.contains(aud):registry.accepts(aud)) || subject == null || subject.isBlank()
        || sid == null || sid.isBlank() || sessions.active(sid, subject) != 1)
      throw new SsoTicketService.SsoTicketException("统一登录会话已失效");
    LoginUser user = users.load(subject);
    if (user == null) throw new SsoTicketService.SsoTicketException("统一登录用户已停用");
    if(scopeContexts!=null&&user.getUserContext()!=null) {
      try{scopeContexts.forSession(user,sid);}
      catch(com.bdemo.iam.common.BizException invalidSelection) {
        throw new SsoTicketService.SsoTicketException("统一登录会话或范围已失效");
      }
    }
    subjects.requireBound(subject, user.getUsername(), aud);
    boolean readOnly = user.isReadOnly() || "READONLY_AUDITOR".equals(user.getAccessMode());
    if (!user.mayEnter(aud))
      throw new SsoTicketService.SsoTicketException("目标系统未被授权");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("active", true);
    result.put("subject", subject);
    result.put("username", user.getUsername());
    result.put("sessionId", sid);
    result.put("audience", aud);
    result.put("localSubject", subjects.localSubject(subject, aud));
    result.put("readOnly", readOnly);
    result.put("superAdmin", user.isSuperAdmin() && !Boolean.TRUE.equals(result.get("readOnly")));
    result.put("accessMode", user.getAccessMode());
    result.put("allowedAppCodes",new TreeSet<>(user.getAllowedAppCodes()));
    result.put("globalRead",com.bdemo.iam.security.AppScopePolicy.globalRead(user));
    result.put("applicationAdmin",!readOnly && (user.isSuperAdmin() || user.getAdminAppCodes().contains(aud.toUpperCase(java.util.Locale.ROOT))));
    result.put("roles", new TreeSet<>(user.getRoles()));
    if(user.getUserContext()!=null) {
      if(registry.contract(aud)<2)throw new SsoTicketService.SsoTicketException("目标系统尚未支持区域上下文V2");
      var consumerContext=scopeContexts.forAudience(user,aud);
      subjects.requireNativeScope(subject,aud,consumerContext);
      result.put("userContext",consumerContext);
    }
    String system=registry==null?com.bdemo.iam.navigation.enums.AppCodeEnum.fromString(aud).getIamSystemCode():registry.permissionSystem(aud);
    if(system==null)throw new SsoTicketService.SsoTicketException("系统权限注册缺失");
    String permissionPrefix = system + ":";
    result.put("permissions",new TreeSet<>(user.getPermissions().stream().filter(p->p.startsWith(permissionPrefix)).toList()));
    result.put("projectIds",new TreeSet<>(subjects.projects(subject,aud)));
    result.putAll(subjects.scopeFor(subject, aud));
    result.remove("local");
    result.put("contractVersion", "1.3");
    try {
      String canonical = result.toString();
      result.put("authorizationVersion", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8))));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    return result;
  }
}
