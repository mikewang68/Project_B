import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Read-only inventory for a future migration; NEVER updates data, IAM, accounts or audit history. */
public class EhmDemoScopePlan {
 static final ObjectMapper JSON=new ObjectMapper();
 static final String PROJECT="PROJECT_B",SOURCE="UNASSIGNED",TARGET="EHMS_DEMO";
 static final Set<String> TYPES=Set.of("asset","asset-dossier","component","measurement-point","device-snapshot","point-quality",
  "sample-telemetry","health-assessment","alarm","work-order","maintenance-case","maintenance-plan","inspection-task","defect",
  "user-task","shift-handover","device-template","calibration-record","configuration-change","spare-part","stock-balance","stock-transaction",
  "spare-reservation","failure-mode","alarm-rule","knowledge-case","fault-code","sla-policy",
  "simulation-control","simulation-progress","simulation-receipt","simulation-round","simulation-failure","simulation-fault-sample");
 static final Set<String> REF_FIELDS=Set.of("assetCode","deviceCode","equipmentCode","componentCode","pointCode","orderNo","workOrderNo",
  "sourceId","alarmNo","planNo","planId","referenceId","referenceNo","partCode","reservationNo","balanceId");
 static String identifier(String value){if(!value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new IllegalArgumentException("INVALID_IDENTIFIER");return value;}
 static boolean virtualReference(String value,Set<String> assets){
  if(value==null)return false;
  if(value.startsWith("SAMPLE-LIVE-TPL-")||value.startsWith("SAMPLE-LIVE-KIT-")||value.startsWith("SAMPLE-LIVE-FM-")||value.startsWith("SAMPLE-LIVE-SHIFT-")||value.startsWith("SAMPLE-LIVE-WH:"))return true;
  return assets.stream().anyMatch(code->value.equals(code)||value.startsWith(code+"-")||value.startsWith(code+":"));
 }
 static boolean references(JsonNode value,Set<String> assets){
  if(value.isObject()){var fields=value.fields();while(fields.hasNext()){var f=fields.next();if(REF_FIELDS.contains(f.getKey())&&f.getValue().isTextual()&&virtualReference(f.getValue().asText(),assets))return true;
   if((f.getValue().isObject()||f.getValue().isArray())&&references(f.getValue(),assets))return true;}}
  else if(value.isArray())for(var child:value)if(references(child,assets))return true;
  return false;
 }
 static boolean selected(String type,String id,JsonNode value,Set<String> assets){
  if(!TYPES.contains(type))return false;
  if(type.equals("asset"))return assets.contains(id);
  if(type.equals("simulation-fault-sample"))return assets.stream().anyMatch(code->id.startsWith(code)&&id.substring(code.length()).matches("-C[0-9]+-ALM"));
  if(type.startsWith("simulation-"))return !type.equals("simulation-progress")||assets.contains(id);
  return virtualReference(id,assets)||references(value,assets);
 }
 static void require(boolean condition,String code){if(!condition)throw new IllegalStateException(code);}
 public static void main(String[] args)throws Exception{
  Set<String> assets=new TreeSet<>();for(var entry:JSON.readTree(Files.readString(Path.of(args[0]))).path("entries"))assets.add("SAMPLE-LIVE-"+entry.path("scopeCode").asText());require(assets.size()==29,"EXPECTED_29_CATALOG_ASSETS");
  String table=identifier(System.getenv("EHM_PERSISTENCE_SCHEMA"))+"."+identifier(System.getenv("EHM_PERSISTENCE_TABLE"));
  var properties=new Properties();properties.setProperty("user",System.getenv("SPRING_DATASOURCE_USERNAME"));properties.setProperty("password",System.getenv("SPRING_DATASOURCE_PASSWORD"));properties.setProperty("connectTimeout","8");properties.setProperty("socketTimeout","45");
  try(var db=DriverManager.getConnection(System.getenv("SPRING_DATASOURCE_URL"),properties)){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
   try(var st=db.createStatement()){st.execute("SET TRANSACTION READ ONLY");}
   try(var st=db.createStatement();var rs=st.executeQuery("SHOW transaction_read_only")){require(rs.next()&&"on".equalsIgnoreCase(rs.getString(1)),"READ_ONLY_NOT_VERIFIED");}
   var inventory=new TreeMap<String,Long>();var candidates=new TreeMap<String,Long>();var remaining=new TreeMap<String,Long>();
   Set<String> found=new HashSet<>();MessageDigest digest=MessageDigest.getInstance("SHA-256");
   try(var q=db.prepareStatement("SELECT aggregate_type,COUNT(*) FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=? GROUP BY aggregate_type")){
    q.setQueryTimeout(35);q.setString(1,PROJECT);q.setString(2,SOURCE);q.setString(3,"EHMS");try(var rs=q.executeQuery()){while(rs.next())inventory.put(rs.getString(1),rs.getLong(2));}
   }
   String placeholders=String.join(",",Collections.nCopies(TYPES.size(),"?"));
   try(var q=db.prepareStatement("SELECT aggregate_type,aggregate_id,payload,updated_at FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type IN ("+placeholders+") ORDER BY aggregate_type,aggregate_id")){
    q.setFetchSize(1000);q.setQueryTimeout(40);q.setString(1,PROJECT);q.setString(2,SOURCE);q.setString(3,"EHMS");int i=4;for(String type:TYPES)q.setString(i++,type);
    try(var rs=q.executeQuery()){while(rs.next()){
     String type=rs.getString(1),id=rs.getString(2),payload=rs.getString(3);var value=JSON.readTree(payload);
     if(!selected(type,id,value,assets))continue;
     if(type.equals("asset")){require(value.path("name").asText().startsWith("[虚拟]")&&!value.path("archived").asBoolean(),"CATALOG_ASSET_NOT_VERIFIED_SYNTHETIC");found.add(id);}
     candidates.merge(type,1L,Long::sum);
     for(String text:List.of(type,id,payload,rs.getTimestamp(4).toString())){digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
    }}
   }
   require(found.equals(assets),"CATALOG_ASSETS_MISSING_DO_NOT_MIGRATE");
   inventory.forEach((type,count)->{long n=count-candidates.getOrDefault(type,0L);if(n>0)remaining.put(type,n);});
   long targetRows;try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=?")){
    q.setQueryTimeout(30);q.setString(1,PROJECT);q.setString(2,TARGET);q.setString(3,"EHMS");try(var rs=q.executeQuery()){rs.next();targetRows=rs.getLong(1);}
   }
   var iam=new LinkedHashMap<String,Object>();var save=db.setSavepoint();
   try{
    try(var q=db.prepareStatement("SELECT region_name,status FROM iam.iam_region_preset WHERE project_id=? AND region_id=?")){
     q.setString(1,PROJECT);q.setString(2,TARGET);try(var rs=q.executeQuery()){boolean exists=rs.next();iam.put("registered",exists);if(exists){iam.put("name",rs.getString(1));iam.put("status",rs.getString(2));}}
    }
    try(var q=db.prepareStatement("SELECT l1_allowed,l2_enabled FROM iam.iam_project_system_grant WHERE project_id=? AND region_id=? AND subsystem_code=?")){
     q.setString(1,PROJECT);q.setString(2,TARGET);q.setString(3,"EHMS");try(var rs=q.executeQuery()){boolean exists=rs.next();iam.put("systemGrantExists",exists);if(exists){iam.put("l1Allowed",rs.getBoolean(1));iam.put("l2Enabled",rs.getBoolean(2));}}
    }
    try(var q=db.prepareStatement("SELECT COUNT(*) FROM iam.iam_project_menu_grant WHERE project_id=? AND region_id=? AND subsystem_code=? AND l1_allowed=TRUE AND l2_checked=TRUE")){
     q.setString(1,PROJECT);q.setString(2,TARGET);q.setString(3,"EHMS");try(var rs=q.executeQuery()){rs.next();iam.put("enabledMenuActions",rs.getLong(1));}
    }
    iam.put("registryReadable",true);
   }catch(SQLException denied){db.rollback(save);iam.clear();iam.put("registryReadable",false);iam.put("sqlState",Objects.toString(denied.getSQLState(),""));}
   iam.put("accountScopeAndNativeProvisioningStillRequireIAMVerification",true);
   var result=new LinkedHashMap<String,Object>();result.put("schema","EHMS_DEMO_SCOPE_PLAN_V1");result.put("databaseReadOnlyVerified",true);result.put("projectId",PROJECT);result.put("sourceRegion",SOURCE);result.put("targetRegion",TARGET);
   result.put("verifiedCatalogAssets",found.size());result.put("candidateCounts",candidates);result.put("preserveInSourceCounts",remaining);result.put("candidateSnapshotSha256",HexFormat.of().formatHex(digest.digest()));result.put("existingTargetRows",targetRows);result.put("iam",iam);
   result.put("performedMigration",false);result.put("historicalAuditPayloadsRemainImmutable",true);result.put("credentialsAndDeviceNamesIncluded",false);
   System.out.println(JSON.writeValueAsString(result));db.rollback();
  }
 }
}
