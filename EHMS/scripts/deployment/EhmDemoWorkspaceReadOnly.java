import com.fasterxml.jackson.databind.*;
import java.sql.*;import java.util.*;import java.nio.file.*;import java.nio.file.attribute.PosixFilePermissions;

public class EhmDemoWorkspaceReadOnly{
 static final ObjectMapper JSON=new ObjectMapper();
 static String env(String key){return Objects.requireNonNullElse(System.getenv(key),"");}
 static String ident(String s){if(!s.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new IllegalArgumentException();return s;}
 public static void main(String[] args)throws Exception{
  String table=ident(env("EHM_PERSISTENCE_SCHEMA"))+"."+ident(env("EHM_PERSISTENCE_TABLE"));
  Properties p=new Properties();p.setProperty("user",env("SPRING_DATASOURCE_USERNAME"));p.setProperty("password",env("SPRING_DATASOURCE_PASSWORD"));
  p.setProperty("connectTimeout","8");p.setProperty("socketTimeout","30");p.setProperty("ApplicationName","EHMS_DEMO_WORKSPACE_READONLY");
  try(var db=DriverManager.getConnection(env("SPRING_DATASOURCE_URL"),p)){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
   try(var q=db.createStatement()){q.execute("SET TRANSACTION READ ONLY");}
   try(var q=db.createStatement();var rs=q.executeQuery("SHOW transaction_read_only")){if(!rs.next()||!"on".equalsIgnoreCase(rs.getString(1)))throw new IllegalStateException();}
   Map<String,Long> counts=new TreeMap<>();
   try(var q=db.prepareStatement("SELECT aggregate_type,COUNT(*) FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=? GROUP BY aggregate_type")){
    q.setQueryTimeout(25);q.setString(1,"PROJECT_B");q.setString(2,"EHMS_DEMO");q.setString(3,"EHMS");
    try(var rs=q.executeQuery()){while(rs.next())counts.put(rs.getString(1),rs.getLong(2));}
   }
   JsonNode marker=null,activity=null;
   try(var q=db.prepareStatement("SELECT aggregate_id,payload FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type=?")){
    q.setString(1,"PROJECT_B");q.setString(2,"EHMS_DEMO");q.setString(3,"EHMS");q.setString(4,"demo-workspace");
    try(var rs=q.executeQuery()){while(rs.next()){if(rs.getString(1).equals("integrated-demo-v1"))marker=JSON.readTree(rs.getString(2));if(rs.getString(1).equals("activity"))activity=JSON.readTree(rs.getString(2));}}
   }
   boolean exported=false;String target=env("EHMS_DEMO_SNAPSHOT_PATH");
   if(!target.isBlank()){
    var output=Path.of(target).toAbsolutePath().normalize();
    if(!output.getParent().equals(Path.of("/opt/project-b-unified/components/EHMS-integrated-demo-20261117-r2/deployment-private"))||Files.exists(output)||Files.isSymbolicLink(output))throw new IllegalArgumentException("SNAPSHOT_PATH_REJECTED");
    List<Map<String,Object>> rows=new ArrayList<>();
    try(var q=db.prepareStatement("SELECT aggregate_type,aggregate_id,payload,updated_at FROM "+table+" WHERE project_id=? AND region_id=? AND subsystem_code=?")){
     q.setQueryTimeout(25);q.setString(1,"PROJECT_B");q.setString(2,"EHMS_DEMO");q.setString(3,"EHMS");
     try(var rs=q.executeQuery()){while(rs.next())rows.add(Map.of("type",rs.getString(1),"id",rs.getString(2),"payload",JSON.readTree(rs.getString(3)),"updatedAt",rs.getTimestamp(4).toInstant().toString()));}
    }
    Files.createFile(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(output,JSON.writeValueAsString(Map.of("projectId","PROJECT_B","regionId","EHMS_DEMO","subsystem","EHMS","rows",rows)),StandardOpenOption.WRITE);
    exported=true;
   }
   Map<String,Object> result=new LinkedHashMap<>();result.put("schema","EHMS_DEMO_WORKSPACE_READONLY_V1");result.put("databaseReadOnlyVerified",true);
   result.put("counts",counts);result.put("initialized",marker!=null);
   result.put("activity",activity==null?Map.of("status","NOT_INITIALIZED"):Map.of("status",activity.path("status").asText(),"ticks",activity.path("ticks").asLong(),"lastTickAt",activity.path("lastTickAt"),"lastError",activity.path("lastError").asText()));
   result.put("verified",marker!=null&&activity!=null&&activity.path("status").asText().equals("RUNNING")&&counts.getOrDefault("asset",0L)>=29&&counts.getOrDefault("measurement-point",0L)>=87&&counts.getOrDefault("asset-dossier",0L)>=29&&counts.getOrDefault("work-order",0L)>=6&&counts.getOrDefault("sample-telemetry",0L)>=2000);
   result.put("privateSnapshotCreated",exported);result.put("payloadsOrCredentialsIncluded",false);result.put("databaseModified",false);
   System.out.println(JSON.writeValueAsString(result));db.rollback();
  }
 }
}
