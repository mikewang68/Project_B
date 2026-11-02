import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.jar.JarFile;

/** Private deployment snapshot/verification; reads existing auth, NEVER writes auth tables. */
public class SimulationDeploymentState {
 static final ObjectMapper JSON=new ObjectMapper();
 static String env(String... keys){for(String k:keys){String v=System.getenv(k);if(v!=null&&!v.isBlank())return v;}return "";}
 static String or(String value,String fallback){return value.isBlank()?fallback:value;}
 static String identifier(String value){if(!value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new IllegalArgumentException("Unsafe database identifier");return value;}
 public static void main(String[] args)throws Exception{
  String mode=args[0],file=args[1],project=env("EHM_SIMULATION_PROJECT_ID");
  if(!project.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalStateException("Explicit project required");
  String schema=identifier(or(env("EHM_PERSISTENCE_SCHEMA","EHM_OPENGAUSS_SCHEMA"),"ehm"));
  String table=schema+"."+identifier(or(env("EHM_PERSISTENCE_TABLE","EHM_OPENGAUSS_TABLE"),"aggregate_store"));
  String url=env("SPRING_DATASOURCE_URL");
  if(url.isBlank())url="jdbc:postgresql://"+env("EHM_OPENGAUSS_HOST")+":"+or(env("EHM_OPENGAUSS_PORT"),"5432")+"/"+env("EHM_OPENGAUSS_DATABASE");
  Properties properties=new Properties();properties.setProperty("user",env("SPRING_DATASOURCE_USERNAME","EHM_OPENGAUSS_USERNAME"));
  properties.setProperty("password",env("SPRING_DATASOURCE_PASSWORD","EHM_OPENGAUSS_PASSWORD"));properties.setProperty("connectTimeout","8");properties.setProperty("socketTimeout","30");
  try(Connection db=DriverManager.getConnection(url,properties)){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
   String authHash=authDigest(db,schema);long rows=0,virtualSnapshots=0;
   Set<String> managed=managedSnapshotKeys(db,table,project);
   if(mode.equals("snapshot")){
    try(var out=Files.newBufferedWriter(Path.of(file),StandardCharsets.UTF_8);
        var query=db.prepareStatement("SELECT aggregate_type,aggregate_id,payload FROM "+table+" WHERE project_id=? ORDER BY aggregate_type,aggregate_id")){
     out.write(JSON.writeValueAsString(Map.of("kind","metadata","project",project,"authDigest",authHash)));out.newLine();query.setString(1,project);
     try(var result=query.executeQuery()){while(result.next()){
      String type=result.getString(1),id=result.getString(2),payload=result.getString(3);
      out.write(JSON.writeValueAsString(Map.of("kind","aggregate","type",type,"id",id,"payload",payload,
              "simulatorMutable",managed.contains(type+":"+id)&&validSnapshotIdentity(type,id,payload))));out.newLine();rows++;
     }}
    }
    System.out.println(JSON.writeValueAsString(Map.of("snapshotRows",rows,"project",project,"authReadOnly",true)));
   }else if(mode.equals("verify")){
    try(var in=Files.newBufferedReader(Path.of(file),StandardCharsets.UTF_8);
        var query=db.prepareStatement("SELECT payload FROM "+table+" WHERE project_id=? AND aggregate_type=? AND aggregate_id=?")){
     var meta=JSON.readTree(in.readLine());if(!meta.path("authDigest").asText().equals(authHash))throw new IllegalStateException("ACCOUNT_ROWS_CHANGED");
     String line;while((line=in.readLine())!=null){var row=JSON.readTree(line);
      if(row.path("type").asText().startsWith("simulation-"))continue;
      query.setString(1,project);query.setString(2,row.path("type").asText());query.setString(3,row.path("id").asText());
      try(var result=query.executeQuery()){
       if(!result.next())throw new IllegalStateException("EXISTING_BUSINESS_RECORD_CHANGED");
       String type=row.path("type").asText(),id=row.path("id").asText(),payload=result.getString(1);
       // Retry after code rollback: ONLY proven simulator-owned live snapshots may advance.
       // Static virtual assets, all non-simulator records, and ALL account rows remain immutable.
       if(row.path("simulatorMutable").asBoolean()&&managed.contains(type+":"+id)
               &&validSnapshotIdentity(type,id,payload)){virtualSnapshots++;continue;}
       if(!row.path("payload").asText().equals(payload))throw new IllegalStateException("EXISTING_BUSINESS_RECORD_CHANGED");
      }rows++;
     }
    }
    var state=state(db,table,project);if(!Boolean.TRUE.equals(state.get("initialized"))||((Number)state.get("assets")).intValue()!=29)throw new IllegalStateException("SIMULATION_NOT_INITIALIZED");
    state.put("existingRowsVerified",rows);state.put("virtualSnapshotsVerified",virtualSnapshots);state.put("accountsUnchanged",true);System.out.println(JSON.writeValueAsString(state));
   }else if(mode.equals("status")){System.out.println(JSON.writeValueAsString(state(db,table,project)));}
   else throw new IllegalArgumentException("Unknown mode");
   db.rollback();
  }
 }
 static Map<String,Object> state(Connection db,String table,String project)throws Exception{
  var output=new LinkedHashMap<String,Object>();output.put("project",project);
  try(var q=db.prepareStatement("SELECT aggregate_type,aggregate_id,payload FROM "+table+" WHERE project_id=? AND (aggregate_type='simulation-control' OR aggregate_type='simulation-round' OR aggregate_type='simulation-failure')")){
   q.setString(1,project);try(var r=q.executeQuery()){while(r.next()){
    var v=JSON.readTree(r.getString(3));switch(r.getString(1)){
     case "simulation-control"->{output.put("initialized",v.path("initialized").asBoolean());output.put("running",v.path("running").asBoolean());output.put("ticks",v.path("ticks").asLong());
      output.put("nextTickAt",v.path("nextTickAt").isTextual()?v.path("nextTickAt").asText():null);}
     case "simulation-round"->output.put("latestSamples",v.path("samplesWritten").asInt());
     case "simulation-failure"->output.put("failure",v.path("code").asText());
    }
   }}
  }
  try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE project_id=? AND aggregate_type='simulation-progress'")){q.setString(1,project);try(var r=q.executeQuery()){r.next();output.put("assets",r.getInt(1));}}
  try(var q=db.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE project_id=? AND aggregate_type='sample-telemetry' AND aggregate_id LIKE 'SAMPLE-LIVE-%'")){q.setString(1,project);try(var r=q.executeQuery()){r.next();output.put("storedSamples",r.getLong(1));}}
  return output;
 }
 static boolean validSnapshotIdentity(String type,String id,String payload)throws Exception{
  var v=JSON.readTree(payload);
  return switch(type){
   case "device-snapshot"->id.equals(v.path("deviceCode").asText())&&"虚拟运行".equals(v.path("ready").asText());
   case "point-quality"->id.equals(v.path("pointCode").asText())&&id.startsWith(v.path("assetCode").asText()+"-");
   default->false;
  };
 }
 static Set<String> managedSnapshotKeys(Connection db,String table,String project)throws Exception{
  String artifact=env("EHM_SIMULATION_ARTIFACT");if(artifact.isBlank())return Set.of();
  Set<String> catalog=new HashSet<>(),progress=new HashSet<>(),keys=new HashSet<>();
  try(var jar=new JarFile(artifact);var in=jar.getInputStream(jar.getJarEntry("BOOT-INF/classes/simulation/equipment-scope.json"))){
   for(var entry:JSON.readTree(in).path("entries"))catalog.add("SAMPLE-LIVE-"+entry.path("scopeCode").asText());
  }
  try(var q=db.prepareStatement("SELECT aggregate_type,aggregate_id,payload FROM "+table+" WHERE project_id=? AND aggregate_type IN ('simulation-progress','measurement-point')")){
   q.setString(1,project);Map<String,JsonNode> points=new HashMap<>();try(var r=q.executeQuery()){while(r.next()){
    String id=r.getString(2);var v=JSON.readTree(r.getString(3));
    if(r.getString(1).equals("simulation-progress")&&catalog.contains(id)&&id.equals(v.path("assetCode").asText()))progress.add(id);
    else if(r.getString(1).equals("measurement-point"))points.put(id,v);
   }}
   for(String id:progress)keys.add("device-snapshot:"+id);
   points.forEach((id,v)->{if(progress.contains(v.path("assetCode").asText())&&id.equals(v.path("code").asText())
     &&id.startsWith(v.path("assetCode").asText()+"-")&&v.path("sourceProtocol").asText().equals("SYNTHETIC")
     &&v.path("sourceAddress").asText().equals("sample://"+id))keys.add("point-quality:"+id);});
  }
  return keys;
 }
 static String authDigest(Connection db,String schema)throws Exception{
  var digest=MessageDigest.getInstance("SHA-256");List<String> tables=new ArrayList<>();
  try(var q=db.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? ORDER BY table_name")){
   q.setString(1,schema);try(var r=q.executeQuery()){while(r.next()){String t=r.getString(1);if(t.endsWith("auth_user")||t.equals("auth_user"))tables.add(identifier(t));}}
  }
  for(String table:tables)try(var q=db.createStatement();var r=q.executeQuery("SELECT * FROM "+schema+"."+table+" ORDER BY user_id")){
   digest.update(table.getBytes(StandardCharsets.UTF_8));var metadata=r.getMetaData();while(r.next())for(int i=1;i<=metadata.getColumnCount();i++){
    String value=Objects.toString(r.getObject(i),"<NULL>");digest.update((metadata.getColumnName(i)+":"+value.length()+":"+value+"\n").getBytes(StandardCharsets.UTF_8));
   }
  }
  return HexFormat.of().formatHex(digest.digest());
 }
}
