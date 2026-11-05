package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.user.dto.UserUpsertRequest;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@EnabledIfEnvironmentVariable(named="IAM_SCOPE_TEST_URL",matches=".+")
class ScopeDatabaseTest {
  Connection connection;JdbcTemplate db;ScopeContextService contexts;ScopeProvisioning provisioning;ScopeManagementService management;
  static final String P="V21_TEST_A",Q="V21_TEST_B",R="R1",APP="WMS",MENU="WMS:v21-reference";
  @BeforeEach void setup()throws Exception {
    String expected=System.getenv("IAM_SCOPE_TEST_DATABASE");
    assertNotNull(expected,"Explicitly approve the dedicated fixture database name before enabling DB tests");
    assertTrue(expected.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"),"Dedicated fixture database name required");
    connection=DriverManager.getConnection(System.getenv("IAM_SCOPE_TEST_URL"),System.getenv("IAM_SCOPE_TEST_USER"),System.getenv("IAM_SCOPE_TEST_PASSWORD"));
    try(var s=connection.createStatement();var result=s.executeQuery("SELECT current_database()")){assertTrue(result.next());assertEquals(expected,result.getString(1),"Tests require the explicitly approved fixture database");}
    connection.setAutoCommit(false);
    db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));contexts=new ScopeContextService(db,true);provisioning=new ScopeProvisioning(db,contexts);management=new ScopeManagementService(db,contexts,provisioning);
    // Existing dedicated fixture databases can run the new regression without
    // being rebuilt. PostgreSQL/openGauss transactional DDL is rolled back below.
    if(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='iam' AND table_name='iam_session' AND column_name='scope_selection_json'",Integer.class)==0)
      db.execute("ALTER TABLE iam.iam_session ADD COLUMN scope_selection_json TEXT");
    db.update("INSERT INTO iam.iam_project_preset(project_id,project_name,status,created_by) VALUES(?,'isolated A','active','TEST'),(?,'isolated B','active','TEST')",P,Q);
    db.update("INSERT INTO iam.iam_region_preset(project_id,region_id,region_name,region_type,status,created_by) VALUES(?,'R1','R1','TEST','active','TEST'),(?,'R2','R2','TEST','active','TEST'),(?,'R1','R1','TEST','active','TEST')",P,P,Q);
    db.update("INSERT INTO iam.iam_project_system_grant(project_id,region_id,subsystem_code,l1_allowed,l2_enabled,updated_by) VALUES(?,'R1','WMS',TRUE,TRUE,'TEST'),(?,'R2','WMS',TRUE,TRUE,'TEST'),(?,'R1','WMS',TRUE,TRUE,'TEST')",P,P,Q);
    db.update("INSERT INTO iam.iam_navigation_menu(stable_menu_key,navigation_app_code,source_app_code,source_menu_id,parent_stable_menu_key,hierarchy_code,normalized_level,menu_name,menu_kind,entry_mode,nav_eligible,source_reference,content_sha256,last_batch_id) VALUES(?,'WMS','WMS','v21-reference','APP:WMS','WMS.V21',2,'isolated reference','PAGE','SPA_ROUTE',1,'TEST',?,'TEST')",MENU,"0".repeat(64));
    for(String op:List.of("VIEW","EXPORT","DELETE")) {
      db.update("INSERT INTO iam.iam_menu_operation(stable_menu_key,op_code,enabled) VALUES(?,?,TRUE)",MENU,op);
      db.update("INSERT INTO iam.iam_project_menu_grant(project_id,region_id,subsystem_code,stable_menu_key,op_code,l1_allowed,l2_checked,updated_by) VALUES(?,'R1','WMS',?,?,TRUE,TRUE,'TEST')",P,MENU,op);
    }
    user("v21testl1","L1_SUPER",null,null,null,false,null);
    user("v21testl2","L2_PROJECT",P,null,null,false,null);
    user("v21testl3","L3_SUBSYSTEM",P,R,APP,false,null);
    user("v21testl4","L4_OPERATOR",P,R,APP,false,"v21testl3");
    user("v21testother","L4_OPERATOR",Q,R,APP,false,"v21testl3");
  }
  void user(String id,String level,String p,String r,String app,boolean readonly,String parent) {
    db.update("INSERT INTO iam.iam_user(id,username,password_hash,display_name,status,user_code,scope_state,admin_level,read_only,project_id,region_id,subsystem_code,inherited_from_user,org_codes) VALUES(?,?,'!NO_LOGIN','test','active',?,'V2_READY',?,?,?,?,?,?,'z-ne,c-dl-port')",id,id,id,level,readonly,p,r,app,parent);
  }
  LoginUser actor(String id) {
    var user=new LoginUser(id,id,"test",false,Set.of());contexts.enrich(user);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));return user;
  }
  void session(String id,String user) {
    db.update("INSERT INTO iam.iam_session(id,user_id,expires_at) VALUES(?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour')",id,user);
  }
  com.bproject.iam.context.UserContext select(String user,String sid,List<ScopeTriple> tuples,boolean global) {
    actor(user);var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute("iam.sessionId",sid);
    return contexts.select(request,tuples,global);
  }
  com.bproject.iam.context.UserContext restored(String user,String sid) {
    var loaded=actor(user);contexts.forSession(loaded,sid);return loaded.getUserContext();
  }
  String role() {
    actor("v21testl3");return management.saveRole(null,new ScopeManagementService.RoleInput("viewer","VIEWER","active",List.of(new ScopeManagementService.Action(MENU,"VIEW"),new ScopeManagementService.Action(MENU,"EXPORT"))));
  }
  @AfterEach void cleanup()throws Exception {SecurityContextHolder.clearContext();if(connection!=null){connection.rollback();connection.close();}}
  @Test void l3CanDefineRoleButCannotGrantOrViewOtherScope() {
    var user=actor("v21testl3");assertTrue(user.getUserContext().mayManage());assertEquals("WMS",user.getUserContext().subsystemCode());assertEquals("IAM",user.getUserContext().serviceAudience());
    assertFalse(user.getAllowedAppCodes().contains("IAM"));
    assertTrue(management.roleOptions().stream().anyMatch(row->row.get("action").equals("VIEW")));
    assertThrows(BizException.class,()->management.saveRole(null,new ScopeManagementService.RoleInput("bad","BAD","active",List.of(new ScopeManagementService.Action(MENU,"CHAIN_SUBMIT")))));
    String role=role();assertNotNull(role);assertTrue(provisioning.visible("ROLE",role,user.getUserContext()));
    assertFalse(provisioning.visible("USER","v21testother",user.getUserContext()));
    assertThrows(BizException.class,()->provisioning.requireOwned("USER","v21testother"));
    db.update("UPDATE iam.iam_navigation_menu SET feature_status='PARTIAL' WHERE stable_menu_key=?",MENU);
    assertTrue(management.roleOptions().stream().anyMatch(row->row.get("action").equals("VIEW")),"Published partial pages retain only their explicitly enabled actions");
    db.update("UPDATE iam.iam_navigation_menu SET feature_status='PLANNED' WHERE stable_menu_key=?",MENU);
    assertTrue(management.roleOptions().isEmpty(),"A planned page cannot be delegated");
  }
  @Test void l4InheritsThreeAttributesAndCannotRequestHigherLevelOrOtherRegion() {
    var creator=actor("v21testl3");String role=role();
    var input=new UserUpsertRequest("newuser","new",null,null,"not-used-password",List.of(role),List.of(),"active",null,null,"L4_OPERATOR",false,null,null,null);
    var plan=provisioning.plan(input);assertEquals(P,plan.projectId());assertEquals(R,plan.regionId());assertEquals(APP,plan.subsystemCode());assertEquals(creator.getUserId(),plan.inheritedFrom());assertEquals(List.of("z-ne","c-dl-port"),plan.orgCodes());
    var tampered=new UserUpsertRequest("newuser","new",null,null,"not-used-password",List.of(role),List.of(),"active",null,null,"L4_OPERATOR",false,P,"R2",APP);
    assertThrows(BizException.class,()->provisioning.plan(tampered));
    var elevated=new UserUpsertRequest("newuser","new",null,null,"not-used-password",List.of(role),List.of(),"active",null,null,"L2_PROJECT",false,P,null,null);
    assertThrows(BizException.class,()->provisioning.plan(elevated));
  }
  @Test void effectiveRoleUnionIsClampedByUpperGrantAndReadonlyOnNextLoad() {
    String role=role();db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl4',?)",role);
    assertTrue(actor("v21testl4").getUserContext().hasAction(MENU,"EXPORT"));
    db.update("UPDATE iam.iam_project_menu_grant SET l2_checked=FALSE WHERE project_id=? AND stable_menu_key=? AND op_code='EXPORT'",P,MENU);
    assertFalse(actor("v21testl4").getUserContext().hasAction(MENU,"EXPORT"));
    db.update("INSERT INTO iam.iam_role_operation_grant(role_id,stable_menu_key,op_code,created_by) VALUES(?,?,'DELETE','TEST')",role,MENU);
    db.update("UPDATE iam.iam_user SET read_only=TRUE WHERE id='v21testl4'");
    var current=actor("v21testl4").getUserContext();assertTrue(current.hasAction(MENU,"VIEW"));assertFalse(current.hasAction(MENU,"DELETE"));
    db.update("UPDATE iam.iam_project_preset SET status='disabled' WHERE project_id=?",P);
    assertEquals(ScopeSql.Mode.DENY,actor("v21testl4").getUserContext().scopeMode());
  }
  @Test void projectAppointmentRecordsItsActualManagerWithoutChangingBusinessScope() {
    var manager=actor("v21testl2");
    var input=new UserUpsertRequest("newadmin","new",null,null,"not-used-password",
        List.of(),List.of(),"active",null,null,"L3_SUBSYSTEM",false,P,R,APP);
    var plan=provisioning.plan(input);
    assertEquals(manager.getUserId(),plan.inheritedFrom());
    assertEquals(P,plan.projectId());assertEquals(R,plan.regionId());assertEquals(APP,plan.subsystemCode());
  }
  @Test void subsystemManagerCanUseIamAudienceWithoutAcquiringIamBusinessScope() {
    var user=actor("v21testl3");
    assertTrue(user.mayEnter("iam"));
    assertEquals(Set.of("WMS"),user.getAllowedAppCodes());
    var business=contexts.forAudience(user,"WMS");
    var managementContext=contexts.forAudience(user,"IAM");
    assertEquals("IAM",managementContext.serviceAudience());
    assertEquals("WMS",managementContext.subsystemCode());
    assertEquals(business.activeScope(),managementContext.activeScope());
    assertEquals(business.my_info(),managementContext.my_info());
    assertFalse(user.isSuperAdmin());
    assertFalse(user.hasPermission("iam:user:list:view"));
    assertThrows(BizException.class,()->contexts.forAudience(user,"SYS"));
    db.update("UPDATE iam.iam_navigation_app SET context_contract_max=2,enabled=1,valid=1,in_nav_scope=1,domain_name='iam.example.invalid' WHERE app_code='IAM'");
    provisioning.syncBindings("v21testl3");
    assertEquals(1,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl3' AND audience='iam'",Integer.class));
    db.update("UPDATE iam.iam_project_system_grant SET l2_enabled=FALSE WHERE project_id=?",P);
    provisioning.syncBindings("v21testl3");
    assertEquals(0,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl3' AND audience='iam'",Integer.class));
    assertFalse(actor("v21testl3").mayEnter("IAM"));
  }
  @Test void projectManagerCanReachIamBeforeEnablingItsFirstBusinessGrant() {
    db.update("UPDATE iam.iam_project_system_grant SET l2_enabled=FALSE WHERE project_id=?",P);
    db.update("UPDATE iam.iam_navigation_app SET context_contract_max=2,enabled=1,valid=1,in_nav_scope=1,domain_name='iam.example.invalid' WHERE app_code='IAM'");
    var user=actor("v21testl2");
    assertEquals(ScopeSql.Mode.DENY,user.getUserContext().scopeMode());
    assertTrue(user.mayEnter("IAM"));
    assertFalse(user.mayEnter("WMS"));
    assertEquals(" AND 1=0 ",contexts.forAudience(user,"IAM").my_info());
    provisioning.syncBindings("v21testl2");
    assertEquals(1,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl2' AND audience='iam'",Integer.class));
    assertFalse(actor("v21testl4").mayEnter("IAM"));
  }
  @Test void projectAdminCanEnableFirstGrantAndCannotChangeUpperAllowance() {
    db.update("UPDATE iam.iam_project_system_grant SET l2_enabled=FALSE WHERE project_id=?",P);
    var user=actor("v21testl2");assertTrue(user.getUserContext().mayManage());assertEquals(ScopeSql.Mode.DENY,user.getUserContext().scopeMode());
    assertThrows(BizException.class,()->management.systemGrant(new ScopeManagementService.SystemGrant(new ScopeTriple(Q,R,APP),true,true,0)));
    management.systemGrant(new ScopeManagementService.SystemGrant(new ScopeTriple(P,R,APP),true,true,0));
    assertEquals(ScopeSql.Mode.SCOPED,actor("v21testl2").getUserContext().scopeMode());
    assertThrows(BizException.class,()->management.systemGrant(new ScopeManagementService.SystemGrant(new ScopeTriple(P,R,APP),true,false,0)),"Stale expectedVersion must not overwrite");
  }
  @Test void sqlAndParameterizedQueriesMatchManuallyEnumeratedRowsWithoutCrossProducts() {
    var filters=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(new ScopeTriple("A","R1","WMS"),new ScopeTriple("B","R2","SYS")));
    String query="SELECT id FROM (VALUES (1,'A','R1','WMS'),(2,'B','R2','SYS'),(3,'A','R2','SYS'),(4,'B','R1','WMS'),(5,'A','R1','SYS')) AS records(id,project_id,region_id,subsystem_code) WHERE (id>0 OR id<0)";
    assertEquals(List.of(1,2),db.queryForList(query+filters.my_info()+" ORDER BY id",Integer.class));
    var named=new NamedParameterJdbcTemplate(db);
    assertEquals(List.of(1,2),named.queryForList(query+filters.my_info_template()+" ORDER BY id",filters.scope_params(),Integer.class));
    assertEquals(List.of(1,2,5),db.queryForList(query+filters.my_project_zone_info()+" ORDER BY id",Integer.class));
  }
  @Test void sharedProjectionNeedsExplicitConsumerActionGrantAndHonorsRevocation() {
    String role=role();db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl4',?)",role);
    db.update("INSERT INTO iam.iam_scope_resource(resource_key,owner_app_code,scope_kind,database_key,table_schema,table_name,alias_profile,enabled) VALUES('v21-shared','PSMS','PROJECT_REGION_SHARED','TEST','demo','orders','STANDARD',TRUE)");
    var guard=new ScopeResourceGuard(db);
    var context=contexts.forAudience(actor("v21testl4"),"WMS");
    assertEquals(" AND 1=0 ",guard.authorize(context,"v21-shared",MENU,"VIEW").condition());
    db.update("INSERT INTO iam.iam_shared_resource_grant(resource_key,project_id,region_id,consumer_app_code,op_code,enabled,updated_by) VALUES('v21-shared',?,'R1','WMS','VIEW',TRUE,'TEST')",P);
    var filter=guard.authorize(context,"v21-shared",MENU,"VIEW");
    assertTrue(filter.condition().contains("region_id = 'R1'"));assertFalse(filter.condition().contains("subsystem_code"),"Registered shared resource projects only the authorized pair");
    assertEquals(" AND 1=0 ",guard.authorize(context,"v21-shared",MENU,"EXPORT").condition());
    db.update("UPDATE iam.iam_shared_resource_grant SET enabled=FALSE WHERE resource_key='v21-shared'");
    assertEquals(" AND 1=0 ",guard.authorize(context,"v21-shared",MENU,"VIEW").condition());
    db.update("UPDATE iam.iam_scope_resource SET alias_profile='client_supplied_sql' WHERE resource_key='v21-shared'");
    assertThrows(BizException.class,()->guard.authorize(context,"v21-shared",MENU,"VIEW"));
  }
  @Test void globalReadonlyRolesCannotContainWriteActionsAndRegistryIsExtensible() {
    actor("v21testl1");
    assertThrows(BizException.class,()->management.saveRole(null,new ScopeManagementService.RoleInput("unsafe","BAD_RO","active",List.of(new ScopeManagementService.Action(MENU,"DELETE")))));
    var id=management.saveRole(null,new ScopeManagementService.RoleInput("readonly","RO","active",List.of(new ScopeManagementService.Action(MENU,"EXPORT"))));
    assertEquals("GLOBAL",db.queryForObject("SELECT role_scope FROM iam.iam_role WHERE id=?",String.class,id));
    management.application(new ScopeManagementService.ApplicationInput("V21NEW","Registered test","v21new","/v21new",true,1,null));
    management.application(new ScopeManagementService.ApplicationInput("V21NEXT","Next registered test","v21next","/v21next",true,1,null));
    assertNotEquals(db.queryForObject("SELECT sort_order FROM iam.iam_navigation_app WHERE app_code='V21NEW'",Integer.class),
        db.queryForObject("SELECT sort_order FROM iam.iam_navigation_app WHERE app_code='V21NEXT'",Integer.class));
    var registry=new ApplicationRegistry(db,contexts);assertTrue(registry.registered("v21new"));
    assertFalse(registry.accepts("v21new"),"Registration without an address cannot receive SSO");
    assertEquals(1,registry.contract("v21new"));
    assertThrows(BizException.class,()->management.application(new ScopeManagementService.ApplicationInput("V21NEW","stale","v21new","/v21new",false,1,99)));
    management.application(new ScopeManagementService.ApplicationInput("V21NEW","Disabled","v21new","/v21new",false,1,1));
    assertFalse(registry.accepts("v21new"));
  }
  @Test void projectReaderUsesProjectRoleAndCurrentUpperScope() {
    actor("v21testl1");
    String role=management.saveRole(null,new ScopeManagementService.RoleInput("project review","PROJECT_VIEW",
        "active",List.of(new ScopeManagementService.Action(MENU,"VIEW")),P));
    assertEquals("PROJECT",db.queryForObject("SELECT role_scope FROM iam.iam_role WHERE id=?",String.class,role));
    db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl2',?)",role);
    var context=actor("v21testl2").getUserContext();
    assertFalse(context.hasAction(MENU,"VIEW"),"R1 alone must not grant VIEW over the common R1+R2 SQL filter");
    assertFalse(context.hasAction(MENU,"DELETE"));
    assertTrue(context.my_info().contains(P));
    session("v21-l2-selected","v21testl2");
    assertTrue(select("v21testl2","v21-l2-selected",List.of(new ScopeTriple(P,R,APP)),false).hasAction(MENU,"VIEW"));
    assertEquals(List.of(new ScopeTriple(P,R,APP)),restored("v21testl2","v21-l2-selected").activeScope());
    db.update("UPDATE iam.iam_project_menu_grant SET l2_checked=FALSE WHERE project_id=?",P);
    assertFalse(restored("v21testl2","v21-l2-selected").hasAction(MENU,"VIEW"),"An old selected session rechecks menu revocation");
  }
  @Test void nativeProvisioningRequiresBusinessRoleAndKeepsSelectableAuthorizedBindings() {
    assertTrue(contexts.nativeScopes(actor("v21testl2"),APP).isEmpty(),"Managing a project is not a business VIEW grant");
    actor("v21testl1");
    String role=management.saveRole(null,new ScopeManagementService.RoleInput("native project reader","NATIVE_READER","active",List.of(new ScopeManagementService.Action(MENU,"VIEW")),P));
    db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl2',?)",role);
    db.update("UPDATE iam.iam_navigation_app SET context_contract_max=2,enabled=1,valid=1,in_nav_scope=1 WHERE app_code='WMS'");
    var user=actor("v21testl2");assertFalse(user.getUserContext().hasAction(MENU,"VIEW"));
    assertEquals(List.of(new ScopeTriple(P,R,APP)),contexts.nativeScopes(user,APP));
    provisioning.syncBindings("v21testl2");
    assertEquals(1,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl2' AND audience='wms'",Integer.class));
    db.update("UPDATE iam.iam_project_menu_grant SET l2_checked=FALSE WHERE project_id=?",P);
    assertTrue(contexts.nativeScopes(actor("v21testl2"),APP).isEmpty());
    provisioning.syncBindings("v21testl2");
    assertEquals(0,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl2' AND audience='wms'",Integer.class));
  }
  @Test void l2RegionalActionCeilingAndSelectedSqlStayWithinExplicitGrants() {
    actor("v21testl1");
    String role=management.saveRole(null,new ScopeManagementService.RoleInput("regional reader","REGIONAL_VIEW","active",
        List.of(new ScopeManagementService.Action(MENU,"VIEW"),new ScopeManagementService.Action(MENU,"EXPORT")),P));
    db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl2',?)",role);
    var all=actor("v21testl2").getUserContext();
    assertFalse(all.hasAction(MENU,"VIEW"));assertFalse(all.hasAction(MENU,"EXPORT"));
    session("v21-l2-one","v21testl2");session("v21-l2-two","v21testl2");
    var selected=select("v21testl2","v21-l2-one",List.of(new ScopeTriple(P,R,APP)),false);
    assertTrue(selected.hasAction(MENU,"VIEW"));assertTrue(selected.hasAction(MENU,"EXPORT"));
    var reloaded=restored("v21testl2","v21-l2-one");
    var audience=contexts.forAudience(com.bdemo.iam.security.JwtAuthFilter.currentUser(),APP);
    assertEquals(selected.activeScope(),audience.activeScope());
    assertEquals(selected.my_info(),audience.my_info());
    String query="SELECT id FROM (VALUES (1,'"+P+"','R1','WMS'),(2,'"+P+"','R2','WMS'),(3,'"+Q+"','R1','WMS')) AS records(id,project_id,region_id,subsystem_code) WHERE (id>0 OR id<0)";
    assertEquals(List.of(1),db.queryForList(query+reloaded.my_info(),Integer.class));
    assertEquals(List.of(1),new NamedParameterJdbcTemplate(db).queryForList(query+reloaded.my_info_template(),reloaded.scope_params(),Integer.class));
    assertEquals(2,restored("v21testl2","v21-l2-two").activeScope().size(),"The second session must keep its own default selection");
    assertThrows(BizException.class,()->select("v21testl2","v21-l2-one",List.of(new ScopeTriple(Q,R,APP)),false));
    assertThrows(BizException.class,()->select("v21testl2","v21-l2-one",List.of(),true));
    db.update("UPDATE iam.iam_project_system_grant SET l2_enabled=FALSE WHERE project_id=? AND region_id='R1'",P);
    var denied=restored("v21testl2","v21-l2-one");
    assertEquals(ScopeSql.Mode.DENY,denied.scopeMode());assertEquals(" AND 1=0 ",denied.my_info());
    assertEquals(List.of(new ScopeTriple(P,"R2",APP)),denied.allowedScopes());
    assertTrue(denied.mayManage(),"L2 must still be able to repair/select a permitted range");
  }
  @Test void l1ExplicitSelectionCannotSilentlyBecomeGlobalAfterScopeRemoval() {
    session("v21-l1-one","v21testl1");
    assertEquals(ScopeSql.Mode.GLOBAL,restored("v21testl1","v21-l1-one").scopeMode());
    select("v21testl1","v21-l1-one",List.of(new ScopeTriple(P,R,APP)),false);
    assertEquals(ScopeSql.Mode.SCOPED,restored("v21testl1","v21-l1-one").scopeMode());
    db.update("UPDATE iam.iam_region_preset SET status='disabled' WHERE project_id=? AND region_id=?",P,R);
    var denied=restored("v21testl1","v21-l1-one");
    assertEquals(ScopeSql.Mode.DENY,denied.scopeMode());
    assertTrue(denied.mayManage(),"L1 retains the IAM management audience to choose a valid scope again");
    assertTrue(com.bdemo.iam.security.JwtAuthFilter.currentUser().mayEnter("IAM"));
    assertEquals(ScopeSql.Mode.DENY,contexts.forAudience(com.bdemo.iam.security.JwtAuthFilter.currentUser(),"IAM").scopeMode());
    assertEquals(ScopeSql.Mode.GLOBAL,select("v21testl1","v21-l1-one",List.of(),true).scopeMode(),"GLOBAL requires a new explicit choice");
    db.update("UPDATE iam.iam_session SET revoked_at=CURRENT_TIMESTAMP,scope_selection_json=NULL WHERE id='v21-l1-one'");
    assertThrows(BizException.class,()->restored("v21testl1","v21-l1-one"));
    assertThrows(BizException.class,()->select("v21testl1","v21-l1-one",List.of(),true));
  }
  @Test void savedSelectionIsBoundToTheActualSessionUserAndMalformedRecordsFailClosed() {
    session("v21-l2-bound","v21testl2");
    select("v21testl2","v21-l2-bound",List.of(new ScopeTriple(P,R,APP)),false);
    assertThrows(BizException.class,()->restored("v21testl3","v21-l2-bound"));
    db.update("UPDATE iam.iam_session SET scope_selection_json='not-json' WHERE id='v21-l2-bound'");
    assertThrows(BizException.class,()->restored("v21testl2","v21-l2-bound"));
  }
  @Test void synchronizationPreservesOldAliasAndRevokesOnlyManagedAudience() {
    String role=role();
    db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES('v21testl4',?)",role);
    db.update("UPDATE iam.iam_navigation_app SET context_contract_max=2 WHERE app_code='WMS'");
    db.update("INSERT INTO iam.sso_subject_binding(subject,audience,local_subject,enabled)"
        + " VALUES('v21testl4','wms','existing-business-actor',1),('v21testl4','psms','legacy-actor',1)");
    provisioning.syncBindings("v21testl4");
    assertEquals("existing-business-actor",db.queryForObject("SELECT local_subject FROM iam.sso_subject_binding"
        + " WHERE subject='v21testl4' AND audience='wms'",String.class));
    assertEquals(1,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl4' AND audience='wms'",Integer.class));
    db.update("UPDATE iam.iam_project_menu_grant SET l2_checked=FALSE WHERE project_id=?",P);
    provisioning.syncBindings("v21testl4");
    assertEquals(0,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl4' AND audience='wms'",Integer.class));
    assertEquals(1,db.queryForObject("SELECT enabled FROM iam.sso_subject_binding WHERE subject='v21testl4' AND audience='psms'",Integer.class));
  }
  @Test void l1CanCreateUnpublishedDirectoryAndPageWithSafeHierarchy() {
    actor("v21testl1");
    var writes=new com.bdemo.iam.navigation.service.NavigationMenuWriteService(db);
    writes.create(new com.bdemo.iam.navigation.service.NavigationMenuWriteService.Create(
        "WMS","WMS:v21-dir","APP:WMS","DIRECTORY","test directory","",""));
    writes.create(new com.bdemo.iam.navigation.service.NavigationMenuWriteService.Create(
        "WMS","WMS:v21-page","WMS:v21-dir","PAGE","test page","/v21-page","/wms/v21-page"));
    var page=db.queryForMap("SELECT normalized_level,enabled,nav_eligible,feature_status FROM iam.iam_navigation_menu"
        + " WHERE stable_menu_key='WMS:v21-page'");
    assertEquals(3,((Number)page.get("normalized_level")).intValue());
    assertEquals(0,((Number)page.get("nav_eligible")).intValue());
    assertEquals("PENDING_VERIFICATION",page.get("feature_status"));
    assertThrows(BizException.class,()->writes.move("WMS:v21-dir","WMS:v21-page"));
    assertThrows(BizException.class,()->writes.move("WMS:v21-page","APP:PSMS"));
    assertThrows(BizException.class,()->writes.create(new com.bdemo.iam.navigation.service.NavigationMenuWriteService.Create(
        "WMS","WMS:v21-page","APP:WMS","PAGE","duplicate","/v21","/wms/v21")));
    assertThrows(BizException.class,()->com.bdemo.iam.navigation.service.NavigationMenuWriteService.path("//outside.invalid"));
    actor("v21testl3");
    assertThrows(BizException.class,()->writes.create(new com.bdemo.iam.navigation.service.NavigationMenuWriteService.Create(
        "WMS","WMS:forbidden","APP:WMS","DIRECTORY","forbidden","","")));
  }
  @Test void editingLegacyQueryEntryPreservesItButCannotIntroduceANewUnsafePath() {
    actor("v21testl1");
    var writes=new com.bdemo.iam.navigation.service.NavigationMenuWriteService(db);
    var prior=java.util.Map.<String,Object>of("navigation_app_code","WMS","menu_kind","PAGE",
        "entry_path","/wms/?page=stock","route_path","/stock");
    assertDoesNotThrow(()->writes.validateEdit(prior,"renamed","/wms/?page=stock","/stock",1,1,1,"PARTIAL"));
    assertThrows(BizException.class,()->writes.validateEdit(prior,null,"/wms/?page=other",null,null,null,null,null));
    assertThrows(BizException.class,()->writes.validateEdit(prior,null,"/other/stock",null,null,null,null,null));
    assertThrows(BizException.class,()->writes.validateEdit(prior,null,null,null,2,null,null,null));
    actor("v21testl3");
    assertThrows(BizException.class,()->writes.validateEdit(prior,"renamed",null,null,null,null,null,null));
  }
}
