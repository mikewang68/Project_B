package com.bdemo.iam.scope;

import com.bdemo.iam.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Server-owned scope selection survives process restart and is isolated by central sid. */
final class ScopeSessionSelection {
  private static final ObjectMapper JSON=new ObjectMapper();
  private final JdbcTemplate db;
  ScopeSessionSelection(JdbcTemplate db){this.db=db;}

  ScopeContextService.Selection load(String userId,String sid) {
    if(sid==null||sid.isBlank())throw BizException.unauthorized("会话标识缺失");
    var rows=db.queryForList("SELECT scope_selection_json FROM iam.iam_session"
        + " WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP",sid,userId);
    if(rows.size()!=1)throw BizException.unauthorized("会话已失效");
    Object raw=rows.get(0).get("scope_selection_json");
    if(raw==null)return null; // Existing sessions retain their documented default selection.
    try {
      if(!(raw instanceof String value)||value.length()>131072)throw new IllegalArgumentException();
      var selection=JSON.readValue(value,ScopeContextService.Selection.class);
      if(selection==null||!Objects.equals(userId,selection.userId())||!Objects.equals(sid,selection.sid())
          ||selection.scopes().size()>512)throw new IllegalArgumentException();
      return selection;
    }catch(Exception invalid){throw BizException.unauthorized("会话范围记录无效，请重新登录");}
  }

  void save(ScopeContextService.Selection selection) {
    final String encoded;
    try{encoded=JSON.writeValueAsString(selection);}
    catch(Exception invalid){throw BizException.badRequest("范围记录格式无效");}
    if(encoded.length()>131072||selection.scopes().size()>512)throw BizException.badRequest("范围数量过多");
    if(db.update("UPDATE iam.iam_session SET scope_selection_json=? WHERE id=? AND user_id=?"
        + " AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP",encoded,selection.sid(),selection.userId())!=1)
      throw BizException.unauthorized("会话已失效");
  }
}
