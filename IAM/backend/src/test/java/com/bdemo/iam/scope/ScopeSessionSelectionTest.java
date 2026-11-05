package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bproject.iam.context.ScopeTriple;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ScopeSessionSelectionTest {
  final JdbcTemplate db=mock(JdbcTemplate.class);
  final ScopeSessionSelection store=new ScopeSessionSelection(db);
  final String read="SELECT scope_selection_json FROM iam.iam_session WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP";
  Map<String,Object> row(Object value){var r=new HashMap<String,Object>();r.put("scope_selection_json",value);return r;}
  ScopeContextService.Selection selection(String user,String sid){return new ScopeContextService.Selection(user,sid,false,List.of(new ScopeTriple("P","R1","WMS")));}
  @Test void noSavedChoiceRetainsDefaultButMissingSessionIsUnauthorized() {
    when(db.queryForList(read,"sid","user")).thenReturn(List.of(row(null)));
    assertNull(store.load("user","sid"));
    assertThrows(BizException.class,()->store.load("other","sid"));
    assertThrows(BizException.class,()->store.load("user",""));
  }
  @Test void chosenTupleIsRestoredOnlyForTheExactUserAndSid()throws Exception {
    var expected=selection("user","sid");
    when(db.queryForList(read,"sid","user")).thenReturn(List.of(row(new ObjectMapper().writeValueAsString(expected))));
    assertEquals(expected,store.load("user","sid"));
    when(db.queryForList(read,"different-sid","user")).thenReturn(List.of(row(new ObjectMapper().writeValueAsString(expected))));
    assertThrows(BizException.class,()->store.load("user","different-sid"));
    when(db.queryForList(read,"sid","other")).thenReturn(List.of(row(new ObjectMapper().writeValueAsString(expected))));
    assertThrows(BizException.class,()->store.load("other","sid"));
  }
  @Test void corruptChoiceCannotBecomeAnUnrestrictedDefault() {
    for(Object bad:List.of("", "{}", "null", "not-json", "x".repeat(131073), 17)) {
      when(db.queryForList(read,"sid","user")).thenReturn(List.of(row(bad)));
      assertThrows(BizException.class,()->store.load("user","sid"));
    }
  }
  @Test void revokedSessionCannotSaveAChoice() {
    var choice=selection("user","sid");
    when(db.update(anyString(),any(Object[].class))).thenReturn(0);
    assertThrows(BizException.class,()->store.save(choice));
    when(db.update(anyString(),any(Object[].class))).thenReturn(1);
    assertDoesNotThrow(()->store.save(choice));
  }
}
