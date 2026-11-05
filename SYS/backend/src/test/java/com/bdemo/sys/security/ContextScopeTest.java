package com.bdemo.sys.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.iam.context.*;
import java.util.*;
import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.bdemo.sys.dict.mapper.DictMapper;
import com.bdemo.sys.config.mapper.ConfigMapper;
import com.bdemo.sys.log.mapper.LogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

class ContextScopeTest {
  UserContext context(boolean readonly,Map<String,Set<String>> operations) {
    var scope=new ScopeTriple("P","R","SYS");var list=List.of(scope);var sql=ScopeSql.build(ScopeSql.Mode.SCOPED,list);
    return new UserContext(2,"central-id","operator","central-id","Operator","L4_OPERATOR",readonly,"SYS","P","R","SYS",ScopeSql.Mode.SCOPED,list,list,Set.of("role"),operations.keySet(),operations,Set.of(),"v1",sql.my_info(),sql.my_project_zone_info(),sql.my_info_template(),sql.my_project_zone_info_template(),sql.scope_params());
  }
  void actor(UserContext context) {
    var user=new LoginUser(context.userId(),context.username(),context.displayName(),false,Set.of("sys:dict:item:view"));
    user.setReadOnly(context.readOnly());user.setUserContext(context);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @Test void exportRequiresItsOwnActionEvenWhenLegacyViewCodeIsShared() {
    var guard=new ContextActionInterceptor();var request=new MockHttpServletRequest("GET","/dict/export");
    actor(context(true,Map.of("SYS:dict",Set.of("VIEW"))));
    assertThrows(com.bdemo.sys.common.BizException.class,()->guard.preHandle(request,new MockHttpServletResponse(),new Object()));
    actor(context(true,Map.of("SYS:dict",Set.of("VIEW","EXPORT","DELETE"))));
    assertTrue(guard.preHandle(request,new MockHttpServletResponse(),new Object()));
    request.setMethod("DELETE");
    assertThrows(com.bdemo.sys.common.BizException.class,()->guard.preHandle(request,new MockHttpServletResponse(),new Object()));
  }
  @Test void listCountStatisticsExportsAndGlobalUpdatesUseTheirRegisteredScope() {
    actor(context(false,Map.of("SYS:logs",Set.of("VIEW"))));
    var configuration=new Configuration();
    for(var type:List.of(DictMapper.class,ConfigMapper.class,LogMapper.class))new MapperAnnotationBuilder(configuration,type).parse();
    for(String method:List.of("selectLogs","countLogs","groupByResult","groupByModule","groupByUser","selectForDiagnosis")) {
      String sql=configuration.getMappedStatement(LogMapper.class.getName()+"."+method).getBoundSql(new HashMap<>()).getSql();
      assertTrue(sql.contains("project_id = 'P'"),method);assertTrue(sql.contains("region_id = 'R'"),method);assertTrue(sql.contains("subsystem_code = 'SYS'"),method);
      assertTrue(sql.contains("scope_kind = 'REGION_SYSTEM'"),method);
    }
    for(String method:List.of("selectTypeById","selectItems","updateItem","logicDeleteType","hardDeleteItemsByType")) {
      String sql=configuration.getMappedStatement(DictMapper.class.getName()+"."+method).getBoundSql(new HashMap<>()).getSql();
      assertTrue(sql.contains("scope_kind='GLOBAL'"),method);
    }
    assertTrue(configuration.getMappedStatement(ConfigMapper.class.getName()+".updateValue").getBoundSql(Map.of()).getSql().contains("scope_kind='GLOBAL'"));
  }
  @Test void centralContextNeedsNoDuplicatedLocalIdentityAndRejectsSqlTampering()throws Exception {
    var loader=mock(LoginUserLoader.class);var sessions=new SysSsoSessions(loader,"http://127.0.0.1","x".repeat(40),true,"");
    var field=SysSsoSessions.class.getDeclaredField("scopeV2");field.setAccessible(true);field.set(sessions,true);
    var resolve=SysSsoSessions.class.getDeclaredMethod("resolve",SysSsoSessions.Binding.class,com.fasterxml.jackson.databind.JsonNode.class);resolve.setAccessible(true);
    var json=new ObjectMapper();var payload=json.createObjectNode();
    payload.put("active",true).put("subject","central-id").put("sessionId","sid").put("localSubject","central-id").put("audience","sys").put("username","operator").put("readOnly",true);
    payload.set("permissions",json.valueToTree(List.of("sys:dict:item:view")));
    payload.set("userContext",json.valueToTree(context(true,Map.of("SYS:dict",Set.of("VIEW")))));
    var user=(LoginUser)resolve.invoke(sessions,new SysSsoSessions.Binding("central-id","sid","central-id"),payload);
    assertEquals("central-id",user.getUserId());assertTrue(user.isReadOnly());verifyNoInteractions(loader);
    ((com.fasterxml.jackson.databind.node.ObjectNode)payload.get("userContext")).put("my_info"," AND 1=1 ");
    assertThrows(java.lang.reflect.InvocationTargetException.class,()->resolve.invoke(sessions,new SysSsoSessions.Binding("central-id","sid","central-id"),payload));
    verifyNoInteractions(loader);
  }
}
