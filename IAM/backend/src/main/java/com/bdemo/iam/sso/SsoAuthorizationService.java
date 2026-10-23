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

  public SsoAuthorizationService(SessionMapper sessions, LoginUserLoader users, SsoSubjectGuard subjects) {
    this.sessions = sessions;
    this.users = users;
    this.subjects = subjects;
  }

  public Map<String, Object> current(String subject, String sid, String audience) {
    String aud = audience == null ? "" : audience.trim().toLowerCase(java.util.Locale.ROOT);
    if (!SsoTicketService.ALLOWED_AUDIENCE.contains(aud) || subject == null || subject.isBlank()
        || sid == null || sid.isBlank() || sessions.active(sid, subject) != 1)
      throw new SsoTicketService.SsoTicketException("统一登录会话已失效");
    LoginUser user = users.load(subject);
    if (user == null) throw new SsoTicketService.SsoTicketException("统一登录用户已停用");
    subjects.requireBound(subject, user.getUsername(), aud);
    boolean global = user.isSuperAdmin() || "FULL_ADMIN".equals(user.getAccessMode())
        || "READONLY_AUDITOR".equals(user.getAccessMode());
    String permissionPrefix = com.bdemo.iam.navigation.enums.AppCodeEnum.fromString(aud).getIamSystemCode() + ":";
    if (!global && user.getScopedApps().stream().noneMatch(app -> app.equalsIgnoreCase(aud))
        && user.getPermissions().stream().noneMatch(p -> p.startsWith(permissionPrefix)))
      throw new SsoTicketService.SsoTicketException("目标系统未被授权");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("active", true);
    result.put("subject", subject);
    result.put("username", user.getUsername());
    result.put("sessionId", sid);
    result.put("audience", aud);
    result.put("localSubject", subjects.localSubject(subject, aud));
    result.put("readOnly", user.isReadOnly() || "READONLY_AUDITOR".equals(user.getAccessMode()));
    result.put("superAdmin", user.isSuperAdmin() && !Boolean.TRUE.equals(result.get("readOnly")));
    result.put("accessMode", user.getAccessMode());
    result.put("roles", new TreeSet<>(user.getRoles()));
    result.put("permissions", new TreeSet<>(user.getPermissions()));
    result.put("projectIds",new TreeSet<>(subjects.projects(subject,aud)));
    result.putAll(subjects.scopeFor(subject, aud));
    result.remove("local");
    result.put("contractVersion", "1.2");
    try {
      String canonical = result.toString();
      result.put("authorizationVersion", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8))));
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    return result;
  }
}
