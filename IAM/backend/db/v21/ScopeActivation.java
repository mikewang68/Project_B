import com.bdemo.iam.scope.ScopeContextService;
import com.bdemo.iam.scope.ScopeProvisioning;
import com.bproject.iam.context.ScopeTriple;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Offline, staged activation of existing identities. Supply a private reviewed
 * manifest; credentials come only from the migration environment. No password
 * or identity is created/replaced, and no business history is backfilled here.
 */
public final class ScopeActivation {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Set<String> LEVELS=Set.of("L1_SUPER","L1_READONLY","L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR");
  private static String text(JsonNode value,String name) {
    String result=value.path(name).asText("");
    if(result.isBlank())throw new IllegalArgumentException("MISSING_MANIFEST_FIELD:"+name);
    return result;
  }
  private static String optional(JsonNode value,String name) {
    return value.path(name).isNull()||value.path(name).asText("").isBlank()?null:value.path(name).asText();
  }
  private static List<String> strings(JsonNode node) {
    if(!node.isArray())throw new IllegalArgumentException("MANIFEST_ARRAY_REQUIRED");
    var values=new ArrayList<String>();
    for(var entry:node) {
      if(!entry.isTextual()||entry.asText().isBlank())throw new IllegalArgumentException("INVALID_MANIFEST_ARRAY");
      values.add(entry.asText());
    }
    return values.stream().distinct().sorted().toList();
  }
  private static String digest(String text)throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }
  private static String protectedIdentity(JdbcTemplate db,String id)throws Exception {
    // Kept only as an in-memory digest; password hashes and Fabric material are
    // never written into the activation report.
    var user=db.queryForMap("SELECT id,password_hash,blockchain_id,blockchain_address FROM iam.iam_user WHERE id=?",id);
    var task=db.queryForList("SELECT * FROM iam.identity_task WHERE user_id=?",id);
    return digest(JSON.writeValueAsString(List.of(user,task)));
  }
  private static void require(boolean okay,String code) {
    if(!okay)throw new IllegalStateException(code);
  }
  private static void requireAccepted(JsonNode manifest,String consumer) {
    String tier=text(manifest,"deploymentTier");
    String expected=tier.equals("ISOLATED")?"CANDIDATE_ACCEPTED":"FORMAL_ACCEPTED";
    require(expected.equals(manifest.path("consumerAttestations").path(consumer).path("state").asText()),
        "CONSUMER_NOT_READY:"+consumer);
    require(manifest.path("consumerAttestations").path(consumer).path("evidenceSha256").asText().matches("[a-f0-9]{64}"),
        "CONSUMER_EVIDENCE_REQUIRED:"+consumer);
  }
  private static Map<String,Object> activate(JdbcTemplate db,ScopeProvisioning provision,
      JsonNode account,JsonNode manifest,String stage)throws Exception {
    String id=text(account,"userId"),username=text(account,"username"),level=text(account,"adminLevel");
    require(LEVELS.contains(level),"INVALID_LEVEL");
    var rows=db.queryForList("SELECT * FROM iam.iam_user WHERE id=? AND deleted=0 FOR UPDATE",id);
    require(rows.size()==1&&username.equals(rows.get(0).get("username")),"IMMUTABLE_ACCOUNT_ID_MISMATCH");
    var before=rows.get(0);
    require("active".equals(before.get("status")),"ACCOUNT_NOT_ACTIVE");
    String project=optional(account,"projectId"),region=optional(account,"regionId"),
        system=optional(account,"subsystemCode"),parent=optional(account,"managerUserId");
    boolean readOnly=account.path("readOnly").asBoolean(false)||level.equals("L1_READONLY");
    if(level.startsWith("L1_")) {
      require(project==null&&region==null&&system==null,"L1_MUST_BE_GLOBAL");
      var requiredConsumers=strings(manifest.path("globalAccountConsumers"));
      var enabledConsumers=db.queryForList(
          "SELECT app_code FROM iam.iam_navigation_app WHERE enabled=1 AND valid=1 ORDER BY app_code",String.class);
      require(!requiredConsumers.isEmpty()&&requiredConsumers.containsAll(enabledConsumers),"GLOBAL_CONSUMER_GATE_INCOMPLETE");
      require(db.queryForObject(
          "SELECT count(*) FROM iam.iam_navigation_app WHERE enabled=1 AND valid=1 AND context_contract_max<2",Integer.class)==0,
          "GLOBAL_CONSUMER_STILL_LEGACY");
      for(var required:requiredConsumers)
        requireAccepted(manifest,required);
    }else {
      ScopeTriple.identifier(project,64);
      require(db.queryForObject("SELECT count(*) FROM iam.iam_project_preset WHERE project_id=? AND status='active'",Integer.class,project)==1,"PROJECT_NOT_ACTIVE");
      if(level.equals("L2_PROJECT"))require(region==null&&system==null,"L2_MUST_BE_PROJECT_SCOPED");
      else {
        var scope=new ScopeTriple(project,region,system);
        require(system.equals(stage),"ACCOUNT_WRONG_ACTIVATION_STAGE");
        requireAccepted(manifest,system);
        require(db.queryForObject("SELECT count(*) FROM iam.iam_navigation_app WHERE app_code=? AND enabled=1 AND valid=1 AND context_contract_max>=2",Integer.class,system)==1,"V2_CONSUMER_NOT_ENABLED");
        provision.requireActive(scope);
        require(parent!=null,"MANAGEMENT_RELATION_REQUIRED");
        var managers=db.queryForList("SELECT admin_level,project_id,region_id,subsystem_code FROM iam.iam_user WHERE id=? AND deleted=0 AND status='active' AND scope_state='V2_READY'",parent);
        require(managers.size()==1,"MANAGER_NOT_READY");
        var manager=managers.get(0);
        require(project.equals(manager.get("project_id")),"MANAGER_PROJECT_MISMATCH");
        if(level.equals("L3_SUBSYSTEM"))require("L2_PROJECT".equals(manager.get("admin_level")),"L3_REQUIRES_L2_MANAGER");
        else require("L3_SUBSYSTEM".equals(manager.get("admin_level"))&&region.equals(manager.get("region_id"))
            &&system.equals(manager.get("subsystem_code")),"L4_INHERITANCE_MISMATCH");
      }
    }
    var roles=strings(account.path("roleIds"));
    var plan=new ScopeProvisioning.Plan("OFFLINE_REVIEWED_ACTIVATION",level,readOnly,project,region,system,parent,List.of());
    provision.validateRoles(plan,roles);
    var currentRoles=db.queryForList("SELECT ur.role_id FROM iam.iam_user_role ur JOIN iam.iam_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.role_scope<>'LEGACY' ORDER BY ur.role_id",String.class,id);
    boolean changed=!"V2_READY".equals(before.get("scope_state"))||!level.equals(before.get("admin_level"))
        ||!Objects.equals(project,before.get("project_id"))||!Objects.equals(region,before.get("region_id"))
        ||!Objects.equals(system,before.get("subsystem_code"))||!Objects.equals(parent,before.get("inherited_from_user"))
        ||!Objects.equals(readOnly,before.get("read_only"))||!roles.equals(currentRoles);
    String protectedBefore=protectedIdentity(db,id);
    if(changed) {
      // Keep legacy relationships for a controlled program rollback. V2 role
      // resolution explicitly excludes LEGACY, so these are not an extra grant.
      db.update("DELETE FROM iam.iam_user_role WHERE user_id=? AND role_id IN(SELECT id FROM iam.iam_role WHERE role_scope<>'LEGACY')",id);
      for(String role:roles)db.update("INSERT INTO iam.iam_user_role(user_id,role_id) VALUES(?,?)",id,role);
      provision.apply(id,plan);
      db.update("UPDATE iam.iam_session SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL",id);
    }else provision.syncBindings(id);
    require(protectedBefore.equals(protectedIdentity(db,id)),"PROTECTED_IDENTITY_CHANGED");
    return Map.of("userId",id,"username",username,"adminLevel",level,"changed",changed,
        "passwordAndFabricPreserved",true,"legacyRoleLinksRetained",true);
  }
  public static void main(String[] args)throws Exception {
    if(args.length!=4||!Set.of("inspect","apply").contains(args[0]))
      throw new IllegalArgumentException("Usage: ScopeActivation inspect|apply STAGE private-manifest.json private-report.json");
    String mode=args[0],stage=args[1];
    Path manifestPath=Path.of(args[2]),reportPath=Path.of(args[3]);
    String raw=Files.readString(manifestPath);
    var manifest=JSON.readTree(raw);
    require(manifest.path("schemaVersion").asInt()==1,"UNSUPPORTED_MANIFEST");
    require(text(manifest,"decisionId").equals(text(manifest,"approvedDecisionId")),"UNREVIEWED_DECISION");
    var output=new LinkedHashMap<String,Object>();
    output.put("mode",mode);output.put("stage",stage);output.put("manifestSha256",digest(raw));
    output.put("startedAt",java.time.Instant.now().toString());
    var results=new ArrayList<Map<String,Object>>();
    try(var connection=DriverManager.getConnection(System.getenv("IAM_DB_URL"),System.getenv("IAM_DB_USER"),System.getenv("IAM_DB_PASSWORD"))) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      try {
        var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        var actual=db.queryForMap("SELECT oid,datname,pg_get_userbyid(datdba) AS owner,datcompatibility,pg_encoding_to_char(encoding) AS encoding FROM pg_database WHERE datname=current_database()");
        var expected=manifest.path("databaseFingerprint");
        for(String key:List.of("oid","datname","owner","datcompatibility","encoding"))
          require(Objects.toString(actual.get(key),"").equals(text(expected,key)),"DATABASE_FINGERPRINT_MISMATCH:"+key);
        require("PG".equals(actual.get("datcompatibility"))&&"UTF8".equals(actual.get("encoding")),"DATABASE_MODE_MISMATCH");
        output.put("databaseFingerprint",actual);
        String tier=text(manifest,"deploymentTier");
        require(Set.of("ISOLATED","FORMAL").contains(tier),"INVALID_DEPLOYMENT_TIER");
        require(tier.equals(text(manifest,"approvedDeploymentTier")),"DEPLOYMENT_TIER_NOT_APPROVED");
        var contexts=new ScopeContextService(db,true);
        var provision=new ScopeProvisioning(db,contexts);
        for(var account:manifest.path("accounts"))if(stage.equals(account.path("activationStage").asText()))
          results.add(activate(db,provision,account,manifest,stage));
        require(!results.isEmpty(),"EMPTY_STAGE");
        output.put("accounts",results);
        if(mode.equals("apply")) {
          output.put("status","PREPARED");
          Files.writeString(reportPath,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
          connection.commit();
          output.put("status","COMMITTED");
        }else {
          connection.rollback();
          output.put("status","INSPECTED_AND_ROLLED_BACK");
        }
      }catch(Exception error) {
        connection.rollback();
        output.put("status","ROLLED_BACK");
        output.put("errorType",error.getClass().getSimpleName());
        if(error instanceof SQLException sql)output.put("sqlState",Objects.toString(sql.getSQLState(),""));
        else if(error instanceof IllegalStateException||error instanceof IllegalArgumentException)
          output.put("reason",Objects.toString(error.getMessage(),""));
        Files.writeString(reportPath,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.exit(1);
      }
    }
    output.put("finishedAt",java.time.Instant.now().toString());
    Files.writeString(reportPath,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    System.out.println(JSON.writeValueAsString(Map.of("status",output.get("status"),"accounts",results.size())));
  }
}
