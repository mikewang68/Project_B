package com.bdemo.iam.provisioning;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.bproject.iam.context.ScopeTriple;
import com.bdemo.iam.scope.ScopeContextService;
import com.bdemo.iam.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Opt-in native identity reconciliation. No passwords, roles or aliases of old people are replaced. */
public final class NativeOnboardingWorker {
  static final ObjectMapper JSON=new ObjectMapper();
  static final String PASSWORD_MARKER="!IAM_MANAGED_V2";
  static final Set<String> MODULES=Set.of("WMS","PSMS","SCS","EHMS","EMCS","TRUST");
  final JsonNode config;final Path state;final Map<String,Db> databases=new HashMap<>();
  record Person(String id,String username,String level,boolean readOnly,String project,String region,String module,
                String alias,String company,String org){}

  NativeOnboardingWorker(JsonNode config)throws Exception {
    this.config=config;state=Path.of(config.path("stateDirectory").asText());
    require(state.isAbsolute()&&Files.isDirectory(state),"STATE_DIRECTORY_REQUIRED");
    Timestamp.valueOf(config.path("createdAfter").asText());
    for(var entry:iterable(config.path("databases").fields()))databases.put(entry.getKey(),new Db(entry.getValue()));
    require(databases.containsKey("IAM"),"CENTRAL_DATABASE_REQUIRED");
  }
  static <T> Iterable<T> iterable(Iterator<T> iterator){return ()->iterator;}
  static void require(boolean valid,String code){if(!valid)throw new IllegalStateException(code);}
  static String value(JsonNode node,String field){String v=node.path(field).asText("");require(v.matches("[A-Za-z0-9_-]{1,64}"),"INVALID_POLICY_IDENTIFIER");return v;}
  static String alias(String module,String subject){require(subject.matches("[a-f0-9]{32}"),"INVALID_NEW_SUBJECT");return module.equals("EMCS")?"lw_"+subject.substring(0,24):subject;}
  static String label(String value,int maximum){return value.codePointCount(0,value.length())<=maximum?value:value.substring(0,value.offsetByCodePoints(0,maximum));}
  static boolean eligible(Person p,JsonNode policy){
    return p.id()!=null&&p.id().matches("[a-f0-9]{32}")&&Set.of("L3_SUBSYSTEM","L4_OPERATOR").contains(p.level())
      &&MODULES.contains(p.module())&&p.project().equals(policy.path("projectId").asText())
      &&p.region().equals(policy.path("regionId").asText())&&p.module().equals(policy.path("subsystemCode").asText())
      &&(p.id().equals(p.alias())||alias(p.module(),p.id()).equals(p.alias()))
      &&(p.company().isBlank()||p.module().equals("WMS")&&p.company().equals(policy.path("companyCode").asText()))
      &&(p.org().isBlank()||p.module().equals("TRUST")&&p.org().equals(policy.path("orgCode").asText()));
  }
  static String policyHash(JsonNode policy)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(policy)));}
  static void atomic(Path file,JsonNode value)throws Exception {
    require(file.isAbsolute()&&Files.isDirectory(file.getParent())&&!Files.isSymbolicLink(file),"UNSAFE_REGISTRY_PATH");
    Path temporary=Files.createTempFile(file.getParent(),".identity-",".tmp");
    try{
      Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
      try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){
        var buffer=java.nio.ByteBuffer.wrap(JSON.writeValueAsBytes(value));while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
      }
      if(Files.exists(file)){
        var attributes=Files.readAttributes(file,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        Files.getFileAttributeView(temporary,PosixFileAttributeView.class).setGroup(attributes.group());
        Files.setPosixFilePermissions(temporary,attributes.permissions());
      }
      Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }finally{Files.deleteIfExists(temporary);}
  }
  static final class Db {
    final JsonNode c;
    Db(JsonNode c){this.c=c;}
    Connection open()throws Exception {
      Properties props=new Properties();props.setProperty("user",c.path("username").asText());props.setProperty("password",c.path("password").asText());
      props.setProperty("connectTimeout","5");props.setProperty("socketTimeout","15");props.setProperty("ApplicationName","iam-native-onboarding");
      Connection db=DriverManager.getConnection(c.path("url").asText(),props);
      try{
        var rows=rows(db,"SELECT oid::text AS oid,datcompatibility AS compatibility,pg_encoding_to_char(encoding) AS encoding FROM pg_database WHERE datname=current_database()");
        require(rows.size()==1&&String.valueOf(rows.get(0).get("oid")).equals(c.path("oid").asText())
          &&String.valueOf(rows.get(0).get("compatibility")).equals(c.path("compatibility").asText())
          &&"UTF8".equals(rows.get(0).get("encoding")),"DATABASE_FINGERPRINT_MISMATCH");return db;
      }catch(Exception e){db.close();throw e;}
    }
  }
  static List<Map<String,Object>> rows(Connection db,String sql,Object... values)throws SQLException {
    try(var p=db.prepareStatement(sql)){p.setQueryTimeout(10);bind(p,values);try(var r=p.executeQuery()){
      var result=new ArrayList<Map<String,Object>>();var m=r.getMetaData();while(r.next()){
        var row=new LinkedHashMap<String,Object>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),r.getObject(i));result.add(row);
      }return result;
    }}
  }
  static int update(Connection db,String sql,Object... values)throws SQLException {
    try(var p=db.prepareStatement(sql)){p.setQueryTimeout(10);bind(p,values);return p.executeUpdate();}
  }
  static void bind(PreparedStatement p,Object[] values)throws SQLException{for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);}
  static long number(Object value){return ((Number)value).longValue();}
  static long id(Connection db,String sql,Object... values)throws SQLException {
    var r=rows(db,sql,values);require(r.size()==1,"UNIQUE_NATIVE_REFERENCE_REQUIRED");return number(r.get(0).values().iterator().next());
  }
  static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  List<Person> people()throws Exception {
    try(var db=databases.get("IAM").open()){
      var rows=rows(db,"""
        SELECT u.id,u.username,u.admin_level,u.read_only,u.project_id,u.region_id,u.subsystem_code,
               b.local_subject,b.company_code,b.org_code
        FROM iam.iam_user u JOIN iam.sso_subject_binding b ON b.subject=u.id AND b.audience=lower(u.subsystem_code) AND b.enabled=1
        JOIN iam.iam_navigation_app a ON a.app_code=u.subsystem_code AND a.enabled=1 AND a.valid=1 AND a.context_contract_max>=2
        JOIN iam.iam_project_system_grant g ON g.project_id=u.project_id AND g.region_id=u.region_id AND g.subsystem_code=u.subsystem_code AND g.l1_allowed=TRUE AND g.l2_enabled=TRUE
        JOIN iam.iam_project_preset p ON p.project_id=u.project_id AND p.status='active'
        JOIN iam.iam_region_preset z ON z.project_id=u.project_id AND z.region_id=u.region_id AND z.status='active'
        WHERE u.created_at>=? AND u.deleted=0 AND u.status='active' AND u.scope_state='V2_READY'
          AND coalesce(b.domain_code,'')='' AND coalesce(b.zone_code,'')=''
          AND u.admin_level IN ('L3_SUBSYSTEM','L4_OPERATOR') ORDER BY u.created_at,u.id
        """,Timestamp.valueOf(config.path("createdAfter").asText()));
      var result=new ArrayList<Person>();for(var r:rows)result.add(new Person(text(r,"id"),text(r,"username"),text(r,"admin_level"),Boolean.TRUE.equals(r.get("read_only")),
        text(r,"project_id"),text(r,"region_id"),text(r,"subsystem_code"),text(r,"local_subject"),text(r,"company_code"),text(r,"org_code")));return result;
    }
  }
  Path journal(Person p){return state.resolve(p.module()+"-"+p.id()+".json");}
  ObjectNode intent(Person p,JsonNode policy)throws Exception {
    return intent(p,policy,null);
  }
  ObjectNode intent(Person p,JsonNode policy,String projectAnchor)throws Exception {
    Path file=journal(p);String hash=policyHash(policy);
    if(Files.exists(file)){
      var saved=(ObjectNode)JSON.readTree(Files.readString(file));
      require(hash.equals(saved.path("policySha256").asText())&&p.id().equals(saved.path("subject").asText()),"EXISTING_IDENTITY_POLICY_CONFLICT");return saved;
    }
    // Check before claiming ownership. An existing identity is never adopted on a name match.
    if(Set.of("SCS","EHMS","EMCS","PSMS").contains(p.module())){
      Path registry=Path.of(policy.path("registry").asText());var saved=JSON.readTree(Files.readString(registry));
      require(saved.isObject()&&!saved.has(p.id()),"PREEXISTING_LOCAL_MAPPING_NOT_OWNED");
    }
    if(Set.of("WMS","PSMS","EMCS","TRUST").contains(p.module())){
      try(var db=databases.get(p.module()).open()){
        String query=switch(p.module()){
          case "WMS" -> "SELECT id FROM auth_user WHERE username=?";
          case "PSMS" -> "SELECT id FROM users WHERE username=?";
          case "EMCS" -> "SELECT user_id FROM sys_user WHERE user_name=?";
          default -> "SELECT username FROM trust_users WHERE username=?";
        };
        require(rows(db,query,alias(p.module(),p.id())).isEmpty(),"PREEXISTING_NATIVE_USER_NOT_OWNED");
      }
    }
    var saved=JSON.createObjectNode().put("subject",p.id()).put("module",p.module()).put("policySha256",hash).put("status","INTENT");
    if(projectAnchor!=null)saved.put("mode","L2_PROJECT").put("anchorSha256",projectAnchor);
    atomic(file,saved);return saved;
  }
  void registry(Person p,JsonNode policy,JsonNode entry)throws Exception {
    Path path=Path.of(policy.path("registry").asText());var root=JSON.readTree(Files.readString(path));require(root.isObject(),"INVALID_NATIVE_REGISTRY");
    if(root.has(p.id()))require(root.path(p.id()).equals(entry),"NATIVE_MAPPING_CONFLICT");
    else{((ObjectNode)root).set(p.id(),entry);atomic(path,root);}
  }
  void nativeDatabase(Person p,JsonNode policy,ObjectNode intent)throws Exception {
    if(Set.of("SCS","EHMS").contains(p.module()))return;
    String local=alias(p.module(),p.id());
    try(var db=databases.get(p.module()).open()){
      db.setAutoCommit(false);
      try{
        switch(p.module()){
          case "WMS" -> {
            long company=id(db,"SELECT id FROM auth_company WHERE code=? AND status='ENABLED'",value(policy,"companyCode"));
            long warehouse=id(db,"SELECT id FROM wms_warehouse WHERE company_id=? AND code=? AND project_id=? AND region_id=? AND subsystem_code='WMS' AND status='ENABLED'",company,value(policy,"warehouseCode"),p.project(),p.region());
            long owner=id(db,"SELECT id FROM wms_owner WHERE company_id=? AND id=? AND status='ENABLED'",company,policy.path("ownerId").asLong());
            long role=id(db,"SELECT id FROM auth_role WHERE code=?",value(policy,"roleCode"));
            var existing=rows(db,"SELECT id,password_hash FROM auth_user WHERE company_id=? AND username=?",company,local);
            if(existing.isEmpty())update(db,"INSERT INTO auth_user(company_id,username,display_name,password_hash,password_change_required) VALUES(?,?,?,?,FALSE)",company,local,p.username(),PASSWORD_MARKER);
            else require(PASSWORD_MARKER.equals(existing.get(0).get("password_hash")),"PREEXISTING_NATIVE_USER_NOT_OWNED");
            long user=id(db,"SELECT id FROM auth_user WHERE company_id=? AND username=?",company,local);
            if(rows(db,"SELECT user_id FROM auth_user_role WHERE user_id=? AND role_id=?",user,role).isEmpty())update(db,"INSERT INTO auth_user_role(user_id,role_id) VALUES(?,?)",user,role);
            if(rows(db,"SELECT user_id FROM auth_user_warehouse WHERE user_id=? AND warehouse_id=?",user,warehouse).isEmpty())update(db,"INSERT INTO auth_user_warehouse(user_id,warehouse_id) VALUES(?,?)",user,warehouse);
            if(rows(db,"SELECT user_id FROM auth_user_owner WHERE user_id=? AND owner_id=?",user,owner).isEmpty())update(db,"INSERT INTO auth_user_owner(user_id,owner_id) VALUES(?,?)",user,owner);
            var mapping=rows(db,"SELECT local_user_id,company_code FROM wms_sso_subject_map WHERE iam_subject=? AND audience='wms'",p.id());
            if(mapping.isEmpty())update(db,"INSERT INTO wms_sso_subject_map(iam_subject,local_user_id,company_code) VALUES(?,?,?)",p.id(),user,value(policy,"companyCode"));
            else require(number(mapping.get(0).get("local_user_id"))==user&&value(policy,"companyCode").equals(mapping.get(0).get("company_code")),"NATIVE_MAPPING_CONFLICT");
            intent.put("nativeUserId",user);
          }
          case "PSMS" -> {
            String actor="IAM-V2-"+p.id();var existing=rows(db,"SELECT id,actor_id,password_hash FROM users WHERE username=? OR id=?",local,actor);
            if(existing.isEmpty())update(db,"INSERT INTO users(id,actor_id,username,password_hash,display_name,role_code) VALUES(?,?,?,?,?,?)",actor,actor,local,PASSWORD_MARKER,p.username(),p.level().equals("L2_PROJECT")?"viewer":"operator");
            else require(existing.size()==1&&actor.equals(existing.get(0).get("id"))&&actor.equals(existing.get(0).get("actor_id"))&&PASSWORD_MARKER.equals(existing.get(0).get("password_hash")),"PREEXISTING_NATIVE_USER_NOT_OWNED");
            for(var scope:policy.path("workAreas")){
              require(scope.asText().matches("[A-Za-z0-9_-]{1,64}"),"INVALID_NATIVE_WORK_AREA");
              if(rows(db,"SELECT actor_id FROM user_data_scopes WHERE actor_id=? AND scope=?",actor,scope.asText()).isEmpty())update(db,"INSERT INTO user_data_scopes(actor_id,scope) VALUES(?,?)",actor,scope.asText());
            }
            require(policy.path("workAreas").isArray()&&!policy.path("workAreas").isEmpty(),"EXPLICIT_NATIVE_WORK_AREA_REQUIRED");intent.put("nativeActorId",actor);
          }
          case "EMCS" -> {
            long role=id(db,"SELECT role_id FROM sys_role WHERE role_key=? AND status='0' AND del_flag='0'",value(policy,"roleKey"));
            var existing=rows(db,"SELECT user_id,password FROM sys_user WHERE user_name=?",local);
            if(existing.isEmpty())update(db,"INSERT INTO sys_user(user_name,nick_name,password,status,del_flag) VALUES(?,?,?,'0','0')",local,label(p.username(),30),PASSWORD_MARKER);
            else require(PASSWORD_MARKER.equals(existing.get(0).get("password")),"PREEXISTING_NATIVE_USER_NOT_OWNED");
            long user=id(db,"SELECT user_id FROM sys_user WHERE user_name=?",local);
            if(rows(db,"SELECT user_id FROM sys_user_role WHERE user_id=? AND role_id=?",user,role).isEmpty())update(db,"INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",user,role);intent.put("nativeUserId",user);
          }
          case "TRUST" -> {
            String org=value(policy,"orgCode");var existing=rows(db,"SELECT password_hash,org_id FROM trust_users WHERE username=?",local);
            if(existing.isEmpty())update(db,"INSERT INTO trust_users(username,password_hash,role,org_id) VALUES(?,?,?,?)",local,PASSWORD_MARKER,p.level().equals("L2_PROJECT")?"VIEWER":"EDITOR",org);
            else require(existing.size()==1&&PASSWORD_MARKER.equals(existing.get(0).get("password_hash"))&&org.equals(existing.get(0).get("org_id")),"PREEXISTING_NATIVE_USER_NOT_OWNED");
          }
          default -> throw new IllegalStateException("UNSUPPORTED_CONSUMER");
        }
        db.commit();
      }catch(Exception e){db.rollback();throw e;}
    }
  }
  void reconcile(Person p,JsonNode policy)throws Exception {
    require(eligible(p,policy),"ACCOUNT_OUTSIDE_MANAGED_POLICY");
    var saved=intent(p,policy);if("COMPLETE".equals(saved.path("status").asText())) {
      stamp(p,List.of(new ScopeTriple(p.project(),p.region(),p.module())),true);return;
    }
    if(saved.path("nextRetryAt").asLong()>System.currentTimeMillis())return;
    try{
      nativeDatabase(p,policy,saved);atomic(journal(p),saved);
      String local=alias(p.module(),p.id());
      switch(p.module()){
        case "SCS","EHMS" -> {
          var entry=JSON.createObjectNode().put("enabled",true).put("localSubject",local).put("role",p.module().equals("SCS")?"OPERATOR":"USER");
          require(policy.path("permissions").isArray()&&!policy.path("permissions").isEmpty(),"EXPLICIT_NATIVE_PERMISSIONS_REQUIRED");entry.set("permissions",policy.path("permissions"));
          if(p.module().equals("EHMS"))entry.set("projectIds",JSON.createArrayNode().add(p.project()));else entry.set("routes",JSON.createArrayNode());
          registry(p,policy,entry);
        }
        case "PSMS" -> registry(p,policy,JSON.createObjectNode().put("enabled",true).put("userId",saved.path("nativeActorId").asText()));
        case "EMCS" -> {
          var entry=JSON.createObjectNode().put("enabled",true).put("localSubject",local).put("userId",saved.path("nativeUserId").asLong());
          require(policy.path("routes").isArray()&&!policy.path("routes").isEmpty(),"EXPLICIT_NATIVE_ROUTES_REQUIRED");entry.set("routes",policy.path("routes"));registry(p,policy,entry);
        }
        default -> {}
      }
      String company=p.module().equals("WMS")?value(policy,"companyCode"):"",org=p.module().equals("TRUST")?value(policy,"orgCode"):"";
      // Updating only a new default binding is the final activation step. Old aliases are never rewritten.
      try(var db=databases.get("IAM").open()){
        int changed=update(db,"""
          UPDATE iam.sso_subject_binding SET local_subject=?,company_code=?,org_code=?,updated_at=CURRENT_TIMESTAMP
          WHERE subject=? AND audience=? AND enabled=1 AND (local_subject=? OR local_subject=?)
            AND (company_code IS NULL OR company_code='' OR company_code=?) AND (org_code IS NULL OR org_code='' OR org_code=?)
          """,local,company,org,p.id(),p.module().toLowerCase(Locale.ROOT),p.id(),local,company,org);
        require(changed==1,"NEW_CENTRAL_BINDING_CHANGED_OR_REVOKED");
      }
      saved.put("status","COMPLETE").put("completedAt",Instant.now().toString()).put("alias",local);saved.remove(List.of("failureCode","nextRetryAt"));atomic(journal(p),saved);
      stamp(p,List.of(new ScopeTriple(p.project(),p.region(),p.module())),true);
    }catch(Exception e){
      int attempts=saved.path("attempts").asInt()+1;saved.put("status","RETRY").put("attempts",attempts)
        .put("failureCode",e instanceof SQLException sql?"SQLSTATE_"+sql.getSQLState():e instanceof IllegalStateException?e.getMessage():e.getClass().getSimpleName())
        .put("nextRetryAt",System.currentTimeMillis()+Math.min(60000,2000L<<Math.min(attempts,5)));atomic(journal(p),saved);throw e;
    }
  }
  void stamp(Person p,List<ScopeTriple> scopes,boolean complete)throws Exception {
    String snapshot=JSON.writeValueAsString(NativeProjectPlan.snapshot(scopes,complete));
    try(var db=databases.get("IAM").open()) {
      int n=update(db,"UPDATE iam.sso_subject_binding SET native_scope_json=?,updated_at=CURRENT_TIMESTAMP WHERE subject=? AND audience=? AND enabled=1"
          +" AND EXISTS (SELECT 1 FROM iam.iam_user u WHERE u.id=subject AND u.status='active' AND u.deleted=0 AND u.scope_state='V2_READY')"
          +" AND (native_scope_json IS NULL OR native_scope_json<>?)",snapshot,p.id(),p.module().toLowerCase(Locale.ROOT),snapshot);
      // An unchanged snapshot is intentionally not rewritten on every polling cycle.
      if(n==0)require(!rows(db,"SELECT subject FROM iam.sso_subject_binding WHERE subject=? AND audience=? AND enabled=1 AND native_scope_json=?",p.id(),p.module().toLowerCase(Locale.ROOT),snapshot).isEmpty(),"NATIVE_BINDING_NO_LONGER_ACTIVE");
    }
  }
  record ProjectPerson(Person person,List<ScopeTriple> scopes) {}
  List<ProjectPerson> projectPeople()throws Exception {
    if(!config.hasNonNull("projectReaderCreatedAfter"))return List.of();
    try(var db=databases.get("IAM").open()) {
      var source=new SingleConnectionDataSource(db,true);var contexts=new ScopeContextService(new JdbcTemplate(source),true);
      var records=rows(db,"SELECT u.id,u.username,u.admin_level,u.read_only,u.project_id,b.audience,b.local_subject,b.company_code,b.org_code"
          +" FROM iam.iam_user u JOIN iam.sso_subject_binding b ON b.subject=u.id AND b.enabled=1"
          +" WHERE u.created_at>=? AND u.deleted=0 AND u.status='active' AND u.scope_state='V2_READY' AND u.admin_level='L2_PROJECT'"
          +" AND coalesce(b.domain_code,'')='' AND coalesce(b.zone_code,'')='' ORDER BY u.id,b.audience",Timestamp.valueOf(config.path("projectReaderCreatedAfter").asText()));
      var result=new ArrayList<ProjectPerson>();
      for(var row:records) {
        String module=text(row,"audience").toUpperCase(Locale.ROOT);if(!MODULES.contains(module))continue;
        String subject=text(row,"id");var login=new LoginUser(subject,text(row,"username"),text(row,"username"),false,Set.of());
        var scopes=contexts.nativeScopes(login,module);
        // A management-only L2 has no business role and creates no native identities.
        if(scopes.isEmpty())continue;
        var person=new Person(subject,text(row,"username"),"L2_PROJECT",true,text(row,"project_id"),"",module,text(row,"local_subject"),text(row,"company_code"),text(row,"org_code"));
        result.add(new ProjectPerson(person,scopes));
      }
      return result;
    }
  }
  void projectRegistry(Person p,JsonNode policy,JsonNode entry,ObjectNode journal)throws Exception {
    Path path=Path.of(policy.path("registry").asText());var root=(ObjectNode)JSON.readTree(Files.readString(path));
    if(root.has(p.id()))require(root.path(p.id()).equals(entry)||journal.has("registryEntry")&&root.path(p.id()).equals(journal.path("registryEntry")),"OWNED_NATIVE_REGISTRY_CHANGED");
    if(!root.path(p.id()).equals(entry)){root.set(p.id(),entry);atomic(path,root);}journal.set("registryEntry",entry);
  }
  void reconcileProject(ProjectPerson candidate)throws Exception {
    try{reconcileProjectReady(candidate);}
    catch(Exception failure) {
      // A conflict or failed native write invalidates this audience's old proof.
      // A merely missing new tuple is handled as PARTIAL by the normal path.
      stamp(candidate.person(),List.of(),false);
      throw failure;
    }
  }
  void reconcileProjectReady(ProjectPerson candidate)throws Exception {
    Person p=candidate.person();require(p.id().equals(p.alias())||alias(p.module(),p.id()).equals(p.alias()),"EXISTING_ALIAS_NOT_AUTO_ADOPTED");
    var plan=NativeProjectPlan.create(candidate.scopes(),config.path("projectReaderScopes"),p.module());
    Path diagnostic=state.resolve("project-"+p.module()+"-"+p.id()+".json");
    ObjectNode status=JSON.createObjectNode().put("subject",p.id()).put("module",p.module()).put("status","BLOCKED");
    var missing=status.putArray("missing");for(var item:plan.missing())missing.addObject().put("projectId",item.scope().projectId()).put("regionId",item.scope().regionId()).put("reason",item.reason());
    if(plan.mappings().isEmpty()){stamp(p,List.of(),false);atomic(diagnostic,status);return;}
    JsonNode policy=plan.merged();require(p.company().isBlank()||p.module().equals("WMS")&&p.company().equals(policy.path("companyCode").asText()),"EXISTING_COMPANY_CLAIM_CONFLICT");
    require(p.org().isBlank()||p.module().equals("TRUST")&&p.org().equals(policy.path("orgCode").asText()),"EXISTING_ORGANIZATION_CLAIM_CONFLICT");
    ObjectNode saved;
    if(Files.exists(journal(p))) {
      saved=(ObjectNode)JSON.readTree(Files.readString(journal(p)));
      require("L2_PROJECT".equals(saved.path("mode").asText())&&p.id().equals(saved.path("subject").asText())&&plan.anchor().equals(saved.path("anchorSha256").asText()),"OWNED_NATIVE_BOUNDARY_CHANGED");
    }else {
      saved=intent(p,policy,plan.anchor());
    }
    var ready=plan.mappings().stream().map(NativeProjectPlan.Mapping::scope).toList();
    var material=JSON.createObjectNode();material.set("policy",policy);var mappingInputs=material.putArray("mappings");
    for(var mapping:plan.mappings())mappingInputs.add(mapping.policy());
    String effectiveHash=policyHash(material);
    if("COMPLETE".equals(saved.path("status").asText())&&effectiveHash.equals(saved.path("effectiveSha256").asText())) {
      stamp(p,ready,plan.missing().isEmpty());
      status.put("status",plan.missing().isEmpty()?"COMPLETE":"PARTIAL").set("readyScopes",NativeProjectPlan.snapshot(ready,true).path("scopes"));
      if(!Files.exists(diagnostic)||!status.equals(JSON.readTree(Files.readString(diagnostic))))atomic(diagnostic,status);
      return;
    }
    atomic(diagnostic,status);
    try {
      for(var mapping:plan.mappings()) {
        var s=mapping.scope();var regional=new Person(p.id(),p.username(),p.level(),true,s.projectId(),s.regionId(),p.module(),p.alias(),p.company(),p.org());
        nativeDatabase(regional,mapping.policy(),saved);
      }
      atomic(journal(p),saved);String local=alias(p.module(),p.id());
      switch(p.module()) {
        case "SCS","EHMS" -> {
          var entry=JSON.createObjectNode().put("enabled",true).put("localSubject",local).put("role",p.module().equals("SCS")?"VIEWER":"USER");
          require(policy.path("permissions").isArray()&&!policy.path("permissions").isEmpty(),"EXPLICIT_NATIVE_PERMISSIONS_REQUIRED");entry.set("permissions",policy.path("permissions"));
          if(p.module().equals("EHMS")){var projects=entry.putArray("projectIds");plan.mappings().stream().map(m->m.scope().projectId()).distinct().sorted().forEach(projects::add);}else entry.putArray("routes");
          projectRegistry(p,policy,entry,saved);
        }
        case "PSMS" -> projectRegistry(p,policy,JSON.createObjectNode().put("enabled",true).put("userId",saved.path("nativeActorId").asText()),saved);
        case "EMCS" -> {
          var entry=JSON.createObjectNode().put("enabled",true).put("localSubject",local).put("userId",saved.path("nativeUserId").asLong());
          require(policy.path("routes").isArray()&&!policy.path("routes").isEmpty(),"EXPLICIT_NATIVE_ROUTES_REQUIRED");entry.set("routes",policy.path("routes"));projectRegistry(p,policy,entry,saved);
        }
        default -> {}
      }
      String company=p.module().equals("WMS")?value(policy,"companyCode"):"",org=p.module().equals("TRUST")?value(policy,"orgCode"):"";
      try(var db=databases.get("IAM").open()) {
        require(update(db,"UPDATE iam.sso_subject_binding SET local_subject=?,company_code=?,org_code=?,updated_at=CURRENT_TIMESTAMP WHERE subject=? AND audience=? AND enabled=1 AND (local_subject=? OR local_subject=?) AND (company_code IS NULL OR company_code='' OR company_code=?) AND (org_code IS NULL OR org_code='' OR org_code=?)",local,company,org,p.id(),p.module().toLowerCase(Locale.ROOT),p.id(),local,company,org)==1,"NEW_CENTRAL_BINDING_CHANGED_OR_REVOKED");
      }
      stamp(p,ready,plan.missing().isEmpty());
      saved.put("status","COMPLETE").put("completedAt",Instant.now().toString()).put("alias",local).put("effectiveSha256",effectiveHash);atomic(journal(p),saved);
      status.put("status",plan.missing().isEmpty()?"COMPLETE":"PARTIAL").set("readyScopes",NativeProjectPlan.snapshot(ready,true).path("scopes"));atomic(diagnostic,status);
    }catch(Exception e) {
      status.put("failureCode",e instanceof SQLException sql?"SQLSTATE_"+sql.getSQLState():e instanceof IllegalStateException?e.getMessage():e.getClass().getSimpleName());atomic(diagnostic,status);throw e;
    }
  }
  ObjectNode cycle()throws Exception {
    var result=JSON.createObjectNode().put("observedAt",Instant.now().toString());int completed=0,failures=0,unmanaged=0;
    for(var p:people()){
      JsonNode policy=null;for(var candidate:config.path("scopes"))if(eligible(p,candidate)){require(policy==null,"DUPLICATE_NATIVE_SCOPE_POLICY");policy=candidate;}
      if(policy==null){unmanaged++;atomic(state.resolve("unmapped-"+p.module()+"-"+p.id()+".json"),JSON.createObjectNode().put("subject",p.id()).put("module",p.module()).put("projectId",p.project()).put("regionId",p.region()).put("status","BLOCKED").put("failureCode","NATIVE_SCOPE_MAPPING_NOT_CONFIGURED"));continue;}
      try{reconcile(p,policy);if("COMPLETE".equals(JSON.readTree(Files.readString(journal(p))).path("status").asText()))completed++;else failures++;}
      catch(Exception e){failures++;} // Per-person diagnostics stay in the private journal; no exception contains a logged credential.
    }
    for(var candidate:projectPeople()) {
      try{reconcileProject(candidate);var status=JSON.readTree(Files.readString(state.resolve("project-"+candidate.person().module()+"-"+candidate.person().id()+".json"))).path("status").asText();if(status.equals("COMPLETE"))completed++;else unmanaged++;}
      catch(Exception e){failures++;var p=candidate.person();atomic(state.resolve("project-"+p.module()+"-"+p.id()+".json"),JSON.createObjectNode().put("subject",p.id()).put("module",p.module()).put("status","BLOCKED").put("failureCode",e instanceof IllegalStateException?e.getMessage():e instanceof SQLException sql?"SQLSTATE_"+sql.getSQLState():e.getClass().getSimpleName()));}
    }
    result.put("completed",completed).put("pendingOrFailed",failures).put("outsidePolicy",unmanaged);atomic(state.resolve("status.json"),result);return result;
  }
  static void run(String[] args)throws Exception {
    require(args.length==1||args.length==2&&args[1].equals("--once"),"CONFIG_PATH_REQUIRED");
    var worker=new NativeOnboardingWorker(JSON.readTree(Files.readString(Path.of(args[0]))));
    try(var file=FileChannel.open(worker.state.resolve("worker.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=file.tryLock()){
      require(lock!=null,"WORKER_ALREADY_RUNNING");
      do{
        try{worker.cycle();}catch(Exception e){atomic(worker.state.resolve("status.json"),JSON.createObjectNode().put("observedAt",Instant.now().toString()).put("status","DEPENDENCY_UNAVAILABLE").put("failureType",e.getClass().getSimpleName()));}
        if(args.length==2)break;Thread.sleep(2000);
      }while(!Thread.currentThread().isInterrupted());
    }
  }
  public static void main(String[] args){
    try{run(args);}catch(Throwable error){System.err.println("NATIVE_ONBOARDING_STARTUP_FAILED:"+error.getClass().getSimpleName());System.exit(1);}
  }
}
