import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;

/** Only aggregate counts and explicitly selected simulator state; no credentials, user rows or device names. */
public class EhmDataScopeReadOnly {
 static final ObjectMapper JSON=new ObjectMapper();
 static String env(String name){return Objects.requireNonNullElse(System.getenv(name),"");}
 static String identifier(String value){if(!value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}"))throw new IllegalArgumentException("INVALID_IDENTIFIER");return value;}
 public static void main(String[] ignored)throws Exception{
  String project="PROJECT_B";
  String table=identifier(env("EHM_PERSISTENCE_SCHEMA"))+"."+identifier(env("EHM_PERSISTENCE_TABLE"));
  Properties properties=new Properties();properties.setProperty("user",env("SPRING_DATASOURCE_USERNAME"));properties.setProperty("password",env("SPRING_DATASOURCE_PASSWORD"));
  properties.setProperty("connectTimeout","8");properties.setProperty("socketTimeout","30");properties.setProperty("ApplicationName","EHMS_READ_ONLY_SCOPE_DIAGNOSTIC");
  try(Connection db=DriverManager.getConnection(env("SPRING_DATASOURCE_URL"),properties)){
   db.setReadOnly(true);db.setAutoCommit(false);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
   try(var statement=db.createStatement()){statement.execute("SET TRANSACTION READ ONLY");}
   try(var statement=db.createStatement();var rs=statement.executeQuery("SHOW transaction_read_only")){
    if(!rs.next()||!"on".equalsIgnoreCase(rs.getString(1)))throw new IllegalStateException("READ_ONLY_NOT_VERIFIED");
   }
   Map<String,Map<String,Object>> regions=new TreeMap<>();
   String[] types={"asset","alarm","work-order","device-snapshot","user-task","shift-handover","simulation-progress","sample-telemetry"};
   String placeholders=String.join(",",Collections.nCopies(types.length,"?"));
   try(var q=db.prepareStatement("SELECT region_id,aggregate_type,COUNT(*),MAX(updated_at) FROM "+table+
     " WHERE project_id=? AND subsystem_code=? AND aggregate_type IN ("+placeholders+") GROUP BY region_id,aggregate_type ORDER BY region_id,aggregate_type")){
    q.setQueryTimeout(25);q.setString(1,project);q.setString(2,"EHMS");for(int i=0;i<types.length;i++)q.setString(i+3,types[i]);
    try(var rs=q.executeQuery()){while(rs.next()){
     var region=regions.computeIfAbsent(rs.getString(1),k->new LinkedHashMap<>());
     region.put(rs.getString(2)+"Rows",rs.getLong(3));region.put(rs.getString(2)+"LastChangedAt",Objects.toString(rs.getTimestamp(4),""));
    }}
   }
   try(var q=db.prepareStatement("SELECT region_id,aggregate_id,payload FROM "+table+" WHERE project_id=? AND subsystem_code=? AND aggregate_type=?")){
    q.setQueryTimeout(20);q.setString(1,project);q.setString(2,"EHMS");q.setString(3,"asset");
    try(var rs=q.executeQuery()){while(rs.next()){
     var region=regions.computeIfAbsent(rs.getString(1),k->new LinkedHashMap<>());var value=JSON.readTree(rs.getString(3));
     String key=value.path("archived").asBoolean()?"archivedAssets":"activeAssets";region.put(key,((Number)region.getOrDefault(key,0L)).longValue()+1);
     if(rs.getString(2).startsWith("SAMPLE-LIVE-"))region.put("sampleLiveAssets",((Number)region.getOrDefault("sampleLiveAssets",0L)).longValue()+1);
    }}
   }
   List<Map<String,Object>> simulation=new ArrayList<>();
   try(var q=db.prepareStatement("SELECT region_id,payload FROM "+table+" WHERE project_id=? AND subsystem_code=? AND aggregate_type=?")){
    q.setQueryTimeout(20);q.setString(1,project);q.setString(2,"EHMS");q.setString(3,"simulation-control");
    try(var rs=q.executeQuery()){while(rs.next()){
     var value=JSON.readTree(rs.getString(2));Map<String,Object> row=new LinkedHashMap<>();row.put("region",rs.getString(1));
     for(String key:List.of("initialized","running","ticks","nextTickAt","lastTickAt"))if(value.has(key))row.put(key,value.get(key));
     simulation.add(row);
    }}
   }
   var result=new LinkedHashMap<String,Object>();result.put("schema","EHMS_READ_ONLY_SCOPE_COUNTS_V1");result.put("databaseReadOnlyVerified",true);
   result.put("projectId",project);result.put("subsystem","EHMS");result.put("regions",regions);result.put("simulation",simulation);
   result.put("credentialsAndDeviceNamesIncluded",false);System.out.println(JSON.writeValueAsString(result));db.rollback();
  }
 }
}
